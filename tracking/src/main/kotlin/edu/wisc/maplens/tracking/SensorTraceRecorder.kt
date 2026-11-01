package edu.wisc.maplens.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import edu.wisc.maplens.model.MotionEvent
import java.io.File
import java.time.Instant
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Latest state of one sensor for the live diagnostics screen.
 *
 * @property status human-readable registration state ("Live", "Waiting for data", ...).
 * @property values last raw sample; for the step counter, the session-relative delta.
 * @property hz observed delivery rate since registration, which can differ from the request.
 */
data class SensorReading(
    val kind: TraceSensor,
    val status: String,
    val values: List<Float> = emptyList(),
    val timestampNs: Long = 0,
    val hz: Double = 0.0,
)

/**
 * Immutable view of the recorder, published to the UI every `recordingUiPeriodMs` and after
 * each command. "Live" counts restart whenever sensors re-register (e.g. after leaving the
 * screen); the `saved*` fields come from the latest finished trace's JSON, so they survive that.
 *
 * @property steps selected-source steps, i.e. what the tracker would receive.
 * @property skippedSteps selected-source steps dropped for lack of an aligned heading.
 * @property checkpoints door taps in the current, or else the last, recording.
 */
data class RecorderSnapshot(
    val recording: Boolean,
    val elapsedMs: Long,
    val steps: Long,
    val rows: Long,
    val readings: List<SensorReading>,
    val message: String,
    val latestFile: File?,
    val heading: HeadingReading?,
    val detectorSteps: Long,
    val peakSteps: Long,
    val usePeaks: Boolean,
    val skippedSteps: Long,
    val savedSteps: Long?,
    val savedDetectorSteps: Long?,
    val savedPeakSteps: Long?,
    val checkpoints: Int,
    val lastCheckpoint: String?,
)

/**
 * Debug sensor recorder (#11): shows live readings and writes walking traces for replay.
 *
 * Threading: sensor callbacks, CSV writes, metadata and every public command run on one
 * `HandlerThread`, so recorder state needs no locks. Public methods only post work to it; the
 * UI learns the outcome through [RecorderSnapshot]s posted back to the main thread.
 *
 * Files, all private to the app under [directory]:
 * - `trace-<start ns>.csv.partial` while recording; renamed to `.csv` only after the JSON
 *   details file is written, so a `.csv` is always complete (see ADR 0002 for the format).
 * - `trace-<start ns>.json` with device, sensors, counts, heading alignment and checkpoints.
 *
 * Timestamps stay in Android's boot clock (`elapsedRealtimeNanos`), the clock sensor events
 * use, so rows and checkpoint taps line up without conversion.
 *
 * @param onMotionEvent receives each selected-source step with its map heading, on the
 *   worker thread; a consumer such as the tracker must hand it to its own thread.
 */
class SensorTraceRecorder(
    context: Context,
    private val directory: File,
    private val config: TrackingConfig = TrackingConfig(),
    onSnapshot: (RecorderSnapshot) -> Unit,
    private val onMotionEvent: (MotionEvent.Step) -> Unit = {},
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val appContext = context.applicationContext
    private val thread = HandlerThread("maplens-trace").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(context.mainLooper)

    /** Cleared by [close] so a late snapshot can't reach a destroyed Activity. */
    @Volatile private var callback: ((RecorderSnapshot) -> Unit)? = onSnapshot

    /** Recorded streams, in the order their cards appear on screen. */
    private val sensors = linkedMapOf(
        TraceSensor.STEP to Sensor.TYPE_STEP_DETECTOR,
        TraceSensor.STEP_COUNTER to Sensor.TYPE_STEP_COUNTER,
        TraceSensor.ACCEL to Sensor.TYPE_ACCELEROMETER,
        TraceSensor.GYRO to Sensor.TYPE_GYROSCOPE,
        TraceSensor.GAME_RV to Sensor.TYPE_GAME_ROTATION_VECTOR,
    )
    private val readings = mutableMapOf<TraceSensor, SensorReading>()

    /** First event time and event count per stream, for the observed-rate display. */
    private val firstTimes = mutableMapOf<TraceSensor, Long>()
    private val counts = mutableMapOf<TraceSensor, Long>()
    private val counter = SessionStepCounter()
    private val motion = SensorMotionProcessor(config)
    private var screenRotation = 0

    /** Display rotation changes during the recording, saved so replay uses the same heading. */
    private var rotations = JSONArray()

    /** Door taps of the current recording, mirrored into the JSON details file. */
    private var checkpoints = JSONArray()
    private var savedSteps: Long? = null
    private var savedDetectorSteps: Long? = null
    private var savedPeakSteps: Long? = null

    /** Last raw since-boot counter value; becomes the baseline when a recording starts. */
    private var lastCounter: Float? = null

    /** Non-null exactly while recording; most commands check it to tell preview from recording. */
    private var writer: TraceCsvWriter? = null
    private var pending: File? = null
    private var latest: File? = null
    private var metadata = JSONObject()
    private var startedNs = 0L
    private var elapsedMs = 0L
    private var steps = 0L
    private var rows = 0L

    /** Sensors are registered (screen visible); false between [pause] and [resume]. */
    private var active = false
    private var message = "Live preview · nothing is saved until you record."

    /** Periodic UI refresh while active, independent of the sensor rate. */
    private val refresh = object : Runnable {
        override fun run() {
            publish()
            if (active) worker.postDelayed(this, config.recordingUiPeriodMs)
        }
    }

    /**
     * Registers sensors and starts the live preview; call from `onResume`. Continuous streams
     * use `recordingPeriodUs`; the step sensors use NORMAL because they are event driven and
     * need the activity-recognition permission on API 29+. If the step detector is unavailable,
     * accelerometer peaks become the selected source so steps still flow.
     */
    fun resume() = worker.post {
        if (active) return@post
        active = true
        latest = directory.listFiles()?.filter { it.extension == "csv" }?.maxByOrNull {
            it.lastModified()
        }
        loadSavedCounts()
        firstTimes.clear()
        counts.clear()
        readings.clear()
        lastCounter = null
        counter.reset()
        motion.reset()
        steps = 0
        sensors.forEach { (kind, type) ->
            val sensor = manager.getDefaultSensor(type)
            val needsPermission = kind == TraceSensor.STEP || kind == TraceSensor.STEP_COUNTER
            val permitted = Build.VERSION.SDK_INT < 29 || !needsPermission ||
                appContext.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
            val status = when {
                sensor == null -> "Unavailable on this phone"
                !permitted -> "Activity permission needed"
                else -> try {
                    val period = if (needsPermission) {
                        SensorManager.SENSOR_DELAY_NORMAL
                    } else {
                        config.recordingPeriodUs
                    }
                    if (manager.registerListener(this, sensor, period, worker)) {
                        "Waiting for data"
                    } else {
                        "Registration failed"
                    }
                } catch (_: SecurityException) {
                    "Permission denied"
                }
            }
            readings[kind] = SensorReading(kind, status)
        }
        val detectorReady = readings[TraceSensor.STEP]?.status == "Waiting for data"
        motion.selectPeaks(!detectorReady)
        worker.post(refresh)
    }

    /** Display rotation (0..3) from the Activity; logged with a time when it changes mid-recording. */
    fun setScreenRotation(rotation: Int) = worker.post {
        require(rotation in 0..3)
        if (screenRotation != rotation && writer != null) {
            rotations.put(
                JSONObject().put("timestampNs", SystemClock.elapsedRealtimeNanos())
                    .put("rotation", rotation)
            )
        }
        screenRotation = rotation
    }

    /**
     * Sets θ0 so the current facing direction reads as [mapBearingRad]. Locked while recording,
     * so a trace has exactly one offset, which is saved in its JSON.
     */
    fun alignHeading(mapBearingRad: Double) = worker.post {
        if (writer != null) return@post
        message = if (motion.align(mapBearingRad, SystemClock.elapsedRealtimeNanos())) {
            "Heading aligned to the selected map direction. Re-align if you change your grip."
        } else {
            "Heading not ready. Hold the screen facing you, pointing along the corridor."
        }
        publish()
    }

    /** Switches the selected step source; locked while recording, like alignment. */
    fun useAccelerometerSteps(enabled: Boolean) = worker.post {
        if (writer != null) return@post
        motion.selectPeaks(enabled)
        steps = 0
        message = "Step source changed; live counts reset. Accelerometer peaks are experimental."
        publish()
    }

    /**
     * Writes a door checkpoint; ignored when not recording. The default timestamp is taken on
     * the caller's (UI) thread at the tap, so time spent in the worker queue doesn't shift it,
     * and it uses the same boot clock as sensor events.
     *
     * @param label from [checkpointLabel], e.g. `door-3445`.
     */
    fun checkpoint(label: String, timestampNs: Long = SystemClock.elapsedRealtimeNanos()) =
        worker.post {
            val csv = writer ?: return@post
            try {
                csv.appendCheckpoint(timestampNs, label)
                rows = csv.rows
                checkpoints.put(JSONObject().put("timestampNs", timestampNs).put("label", label))
                message = "Checkpoint $label marked."
            } catch (error: Exception) {
                fail(error)
            }
            publish()
        }

    /** Starts a new trace file; no-op while paused or already recording. */
    fun start() = worker.post {
        if (!active || writer != null) return@post
        if (readings.values.none { it.status == "Live" || it.status == "Waiting for data" }) {
            message = "No sensors could be registered. Check sensor availability and permissions."
            publish()
            return@post
        }
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create recordings folder" }
            startedNs = SystemClock.elapsedRealtimeNanos()
            pending = File(directory, "trace-$startedNs.csv.partial")
            writer = TraceCsvWriter(pending!!.bufferedWriter())
            steps = 0
            motion.resetCounts()
            rows = 0
            elapsedMs = 0
            checkpoints = JSONArray()
            // Count only steps taken from now on, never the phone's since-boot total.
            counter.reset(lastCounter)
            readings[TraceSensor.STEP_COUNTER]?.let {
                readings[TraceSensor.STEP_COUNTER] = it.copy(values = emptyList())
            }
            metadata = JSONObject().apply {
                put("formatVersion", 1)
                put("startedUtc", Instant.now().toString())
                put("startedElapsedRealtimeNs", startedNs)
                put("clock", "elapsedRealtimeNanos; sensor timestamps retained")
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("androidApi", Build.VERSION.SDK_INT)
                put("requestedContinuousPeriodUs", config.recordingPeriodUs)
                put("counter", "session delta; first reading is baseline if preview has none")
                put("checkpointsImplemented", true)
                put("headingOffsetRad", motion.offsetRad ?: JSONObject.NULL)
                put("headingFrame", "clockwise map radians; atan2(dy, dx); manual alignment")
                put("stepSource", if (motion.usePeaks) "accel_peak" else "step_detector")
                rotations = JSONArray().put(
                    JSONObject().put("timestampNs", startedNs)
                        .put("rotation", screenRotation)
                )
                put(
                    "sensors",
                    JSONObject().apply {
                        readings.forEach { (kind, reading) ->
                            val sensor = manager.getDefaultSensor(sensors.getValue(kind))
                            put(
                                kind.wireName,
                                JSONObject().apply {
                                    put("status", reading.status)
                                    put("name", sensor?.name ?: "unavailable")
                                    put("vendor", sensor?.vendor ?: "unavailable")
                                    put("version", sensor?.version)
                                }
                            )
                        }
                    }
                )
            }
            message = "Recording locally · keep this screen open."
        } catch (error: Exception) {
            fail(error)
        }
        publish()
    }

    /** Finishes and saves the current trace; the live preview keeps running. */
    fun stop() = worker.post {
        finish("Saved on this phone.")
        publish()
    }

    /**
     * Unregisters every sensor and saves any recording in progress; call from `onPause`. The
     * recorder does not record in the background, which keeps it simple and saves battery.
     */
    fun pause() = worker.post {
        active = false
        manager.unregisterListener(this)
        worker.removeCallbacks(refresh)
        finish("Saved automatically when you left the recorder.")
        publish()
    }

    /** Deletes every private trace and JSON file; exported copies are untouched. */
    fun deleteRecordings() = worker.post {
        if (writer != null) return@post
        try {
            directory.listFiles()?.filter { it.name.startsWith("trace-") }?.forEach {
                check(it.delete()) { "Could not delete ${it.name}" }
            }
            latest = null
            loadSavedCounts()
            message = "Local recordings deleted. Exported copies are managed separately."
        } catch (error: Exception) {
            message = "Delete failed: ${error.message}"
        }
        publish()
    }

    /** Final shutdown from `onDestroy`: saves, then stops the worker thread. */
    fun close() {
        callback = null
        worker.post {
            active = false
            manager.unregisterListener(this)
            worker.removeCallbacks(refresh)
            finish("Saved.")
            thread.quitSafely()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val kind = sensors.entries.firstOrNull { it.value == event.sensor.type }?.key ?: return
        val values = when (kind) {
            TraceSensor.GAME_RV -> event.values.copyOf(4).also {
                if (event.values.size < 4) {
                    it[3] = sqrt(
                        (1f - it[0] * it[0] - it[1] * it[1] - it[2] * it[2])
                            .coerceAtLeast(0f)
                    )
                }
            }
            TraceSensor.STEP_COUNTER -> {
                lastCounter = event.values[0]
                floatArrayOf(counter.observe(event.values[0]))
            }
            else -> event.values.copyOf(kind.components)
        }
        val first = firstTimes.getOrPut(kind) { event.timestamp }
        val count = counts.getOrPut(kind) { 0 } + 1
        counts[kind] = count
        val seconds = (event.timestamp - first) / 1e9
        readings[kind] = SensorReading(
            kind, "Live", values.toList(), event.timestamp,
            if (seconds > 0) (count - 1) / seconds else 0.0,
        )
        // Android may still deliver samples measured before Start; keep them out of the
        // session counts and the file. The live readings above still show them.
        if (writer != null && event.timestamp < startedNs) return
        val step = when (kind) {
            TraceSensor.GAME_RV -> {
                motion.rotation(event.timestamp, values, screenRotation)
                null
            }
            TraceSensor.ACCEL -> motion.acceleration(event.timestamp, values)
            TraceSensor.STEP -> motion.detector(event.timestamp)
            else -> null
        }
        steps = motion.steps
        // This callback runs on the sensor worker; the session must enqueue its own state updates.
        step?.let(onMotionEvent)
        val csv = writer ?: return
        if (event.timestamp < startedNs) return
        try {
            csv.append(event.timestamp, kind, values)
            rows = csv.rows
        } catch (error: Exception) {
            fail(error)
            publish()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Closes the CSV, writes the JSON details, then renames `.csv.partial` to `.csv`. The rename
     * comes last, so a crash part-way leaves only a `.partial` file, which is never exported.
     */
    private fun finish(reason: String) {
        val csv = writer ?: return
        writer = null
        elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000
        try {
            csv.close()
            val file = checkNotNull(pending)
            val destination = File(directory, file.name.removeSuffix(".partial"))
            metadata.put("endedUtc", Instant.now().toString())
            metadata.put("durationMs", elapsedMs)
            metadata.put("rows", rows)
            metadata.put("detectedSteps", motion.detectorSteps)
            metadata.put("estimatedPeakSteps", motion.peakSteps)
            metadata.put("selectedSourceSteps", steps)
            metadata.put(
                "counterSteps",
                readings[TraceSensor.STEP_COUNTER]
                    ?.values?.firstOrNull()?.toLong() ?: JSONObject.NULL
            )
            metadata.put("stepsWithoutUsableHeading", motion.skippedSteps)
            metadata.put("screenRotations", rotations)
            metadata.put("checkpoints", checkpoints)
            File(directory, destination.nameWithoutExtension + ".json")
                .writeText(metadata.toString(2))
            check(file.renameTo(destination)) { "Could not finalize recording" }
            latest = destination
            loadSavedCounts()
            message = reason
        } catch (error: Exception) {
            message = "Save failed: ${error.message}. Partial data remains private on this phone."
        }
        pending = null
    }

    /** Abandons the recording after an I/O error; its `.partial` file stays private. */
    private fun fail(error: Exception) {
        try {
            writer?.close()
        } catch (_: Exception) { /* Preserve the original error. */ }
        writer = null
        message = "Recording failed: ${error.message}. " +
            "Retry when ready; partial data is not exported."
    }

    /** Reloads the "last saved" totals from the newest trace's JSON, for display after resume. */
    private fun loadSavedCounts() {
        savedSteps = null
        savedDetectorSteps = null
        savedPeakSteps = null
        val file = latest ?: return
        try {
            val details =
                JSONObject(File(directory, file.nameWithoutExtension + ".json").readText())
            savedDetectorSteps = details.optLong("detectedSteps")
            savedSteps = details.optLong("selectedSourceSteps", savedDetectorSteps!!)
            if (details.has("estimatedPeakSteps")) {
                savedPeakSteps = details.getLong("estimatedPeakSteps")
            }
        } catch (_: Exception) {
            // Legacy or interrupted metadata must not prevent opening the recorder.
        }
    }

    /** Posts a snapshot to the main thread; a heading older than `headingFreshnessNs` shows as unavailable. */
    private fun publish() {
        if (writer != null) {
            elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000
        }
        val snapshot = RecorderSnapshot(
            writer != null,
            elapsedMs,
            steps,
            rows,
            readings.values.toList(),
            message,
            latest,
            motion.heading?.takeIf {
                SystemClock.elapsedRealtimeNanos() - it.timestampNs <= config.headingFreshnessNs
            },
            motion.detectorSteps,
            motion.peakSteps,
            motion.usePeaks,
            motion.skippedSteps,
            savedSteps,
            savedDetectorSteps,
            savedPeakSteps,
            checkpoints.length(),
            checkpoints.optJSONObject(checkpoints.length() - 1)?.getString("label"),
        )
        main.post { callback?.invoke(snapshot) }
    }
}
