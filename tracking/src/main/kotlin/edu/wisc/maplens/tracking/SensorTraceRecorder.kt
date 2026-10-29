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
import java.io.File
import java.time.Instant
import kotlin.math.sqrt
import org.json.JSONObject

data class SensorReading(
    val kind: TraceSensor,
    val status: String,
    val values: List<Float> = emptyList(),
    val timestampNs: Long = 0,
    val hz: Double = 0.0,
)

data class RecorderSnapshot(
    val recording: Boolean,
    val elapsedMs: Long,
    val steps: Long,
    val rows: Long,
    val readings: List<SensorReading>,
    val message: String,
    val latestFile: File?,
)

/** Sensor callbacks, CSV writes, metadata and lifecycle commands share one worker queue. */
class SensorTraceRecorder(
    context: Context,
    private val directory: File,
    private val config: TrackingConfig = TrackingConfig(),
    onSnapshot: (RecorderSnapshot) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val appContext = context.applicationContext
    private val thread = HandlerThread("maplens-trace").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(context.mainLooper)

    @Volatile private var callback: ((RecorderSnapshot) -> Unit)? = onSnapshot
    private val sensors = linkedMapOf(
        TraceSensor.STEP to Sensor.TYPE_STEP_DETECTOR,
        TraceSensor.STEP_COUNTER to Sensor.TYPE_STEP_COUNTER,
        TraceSensor.ACCEL to Sensor.TYPE_ACCELEROMETER,
        TraceSensor.GYRO to Sensor.TYPE_GYROSCOPE,
        TraceSensor.GAME_RV to Sensor.TYPE_GAME_ROTATION_VECTOR,
    )
    private val readings = mutableMapOf<TraceSensor, SensorReading>()
    private val firstTimes = mutableMapOf<TraceSensor, Long>()
    private val counts = mutableMapOf<TraceSensor, Long>()
    private val counter = SessionStepCounter()
    private var lastCounter: Float? = null
    private var writer: TraceCsvWriter? = null
    private var pending: File? = null
    private var latest: File? = null
    private var metadata = JSONObject()
    private var startedNs = 0L
    private var elapsedMs = 0L
    private var steps = 0L
    private var rows = 0L
    private var active = false
    private var message = "Live preview · nothing is saved until you record."
    private val refresh = object : Runnable {
        override fun run() {
            publish()
            if (active) worker.postDelayed(this, config.recordingUiPeriodMs)
        }
    }

    fun resume() = worker.post {
        if (active) return@post
        active = true
        latest = directory.listFiles()?.filter { it.extension == "csv" }?.maxByOrNull {
            it.lastModified()
        }
        firstTimes.clear()
        counts.clear()
        readings.clear()
        lastCounter = null
        counter.reset()
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
        worker.post(refresh)
    }

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
            rows = 0
            elapsedMs = 0
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
                put("checkpointsImplemented", false)
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

    fun stop() = worker.post {
        finish("Saved on this phone.")
        publish()
    }

    fun pause() = worker.post {
        active = false
        manager.unregisterListener(this)
        worker.removeCallbacks(refresh)
        finish("Saved automatically when you left the recorder.")
        publish()
    }

    fun deleteRecordings() = worker.post {
        if (writer != null) return@post
        try {
            directory.listFiles()?.filter { it.name.startsWith("trace-") }?.forEach {
                check(it.delete()) { "Could not delete ${it.name}" }
            }
            latest = null
            message = "Local recordings deleted. Exported copies are managed separately."
        } catch (error: Exception) {
            message = "Delete failed: ${error.message}"
        }
        publish()
    }

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
        val csv = writer ?: return
        if (event.timestamp < startedNs) return
        try {
            csv.append(event.timestamp, kind, values)
            if (kind == TraceSensor.STEP) steps++
            rows = csv.rows
        } catch (error: Exception) {
            fail(error)
            publish()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
            metadata.put("detectedSteps", steps)
            File(directory, destination.nameWithoutExtension + ".json")
                .writeText(metadata.toString(2))
            check(file.renameTo(destination)) { "Could not finalize recording" }
            latest = destination
            message = reason
        } catch (error: Exception) {
            message = "Save failed: ${error.message}. Partial data remains private on this phone."
        }
        pending = null
    }

    private fun fail(error: Exception) {
        try {
            writer?.close()
        } catch (_: Exception) { /* Preserve the original error. */ }
        writer = null
        message = "Recording failed: ${error.message}. " +
            "Retry when ready; partial data is not exported."
    }

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
        )
        main.post { callback?.invoke(snapshot) }
    }
}
