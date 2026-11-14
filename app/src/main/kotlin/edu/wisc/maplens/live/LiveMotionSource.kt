package edu.wisc.maplens.live

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
import android.os.Looper
import android.os.SystemClock
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.tracking.ParticleTracker
import edu.wisc.maplens.tracking.SensorMotionProcessor
import edu.wisc.maplens.tracking.SignResult
import edu.wisc.maplens.tracking.SignSighting
import edu.wisc.maplens.tracking.TraceCsvWriter
import edu.wisc.maplens.tracking.TraceSensor
import edu.wisc.maplens.tracking.Tracker
import edu.wisc.maplens.tracking.TrackingConfig
import edu.wisc.maplens.tracking.WalkingGate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the banner and session log show about the live sensors after each step. */
data class LiveStats(
    val steps: Long,
    val skipped: Long,
    val usingPeaks: Boolean,
    /** When the tracker last re-calibrated k (#12), and to what; null until it has. */
    val calibratedAtNanos: Long? = null,
    val calibratedStepLengthPx: Double? = null,
    /** Step candidates rejected by the walking gate. */
    val rejected: Long = 0,
)

/**
 * Live sensors → [SensorMotionProcessor] (Johnny's #40) → [WalkingGate] → [Tracker] →
 * [PoseEstimate]. The gate checks every footfall against recent motion before advancing.
 *
 * Sensor callbacks, the processor and the tracker all run on one background thread, so none of
 * them needs locks and nothing touches the UI thread (section 7, threading). Poses are posted to
 * the main thread, where `NavigationSession` stays the only writer of position.
 *
 * Phone held in front, portrait locked (the carry mode), so the screen rotation is always 0.
 */
class LiveMotionSource(
    context: Context,
    private val onPose: (PoseEstimate, LiveStats) -> Unit,
    config: TrackingConfig = TrackingConfig(),
    /**
     * Optional: the aligned map heading between steps, posted to the main thread at up to
     * ~30 Hz while the tracker is fed, so a direction cone can turn as the user turns.
     */
    private val onHeading: ((Double) -> Unit)? = null,
    /**
     * Optional: stats after a detected step is rejected by the walking gate.
     */
    private val onStats: ((LiveStats) -> Unit)? = null,
    /**
     * Optional: whether the phone still bobs like a walker's, about 10 times a second while
     * the tracker is fed, so the display can stop the user as soon as they stop.
     */
    private val onMoving: ((Boolean) -> Unit)? = null,
    /**
     * Optional: a folder to record each live walk's raw sensors into (`trace-*.csv`, the
     * recorder's format), so a walk that felt wrong can be replayed exactly offline.
     */
    private val traceDir: File? = null,
    /** Optional: every detected footfall with the walking gate's verdict and the bob then. */
    private val onCandidate: ((Long, String, Double?) -> Unit)? = null,
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(SensorManager::class.java)
    private val periodUs = config.recordingPeriodUs
    private val processor = SensorMotionProcessor(config)
    private val gate = WalkingGate(config)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    /** Only touched on the worker thread. */
    private var tracker: Tracker? = null

    val hasHeadingSensor: Boolean
        get() = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null

    val hasStepPermission: Boolean
        get() = Build.VERSION.SDK_INT < 29 ||
            appContext.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts listening; heading needs a moment to settle before [begin]. Uses the step detector
     * when the phone has one and the permission is granted, otherwise accelerometer peaks.
     */
    fun start(): Boolean {
        val sensors = manager ?: return false
        if (thread != null) return true
        val rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return false
        val t = HandlerThread("maplens-live").apply { start() }
        val h = Handler(t.looper)
        thread = t
        worker = h
        h.post {
            processor.reset()
            gate.reset()
        }
        sensors.registerListener(this, rotation, periodUs, h)
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, periodUs, h)
        }
        // The walking gate needs rotation rate as evidence for accepting a footfall.
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors.registerListener(this, it, periodUs, h)
        }
        val detector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val detectorOn = detector != null && hasStepPermission &&
            sensors.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, h)
        // Fused when the detector is there: it misses steps for seconds at a time, and
        // accelerometer peaks (checked by the walking gate like every footfall) fill the gaps.
        h.post { if (detectorOn) processor.selectFused() else processor.selectPeaks(true) }
        return true
    }

    fun stop() {
        manager?.unregisterListener(this)
        val t = thread ?: return
        worker?.post {
            tracker = null
            closeTrace()
        }
        t.quitSafely()
        thread = null
        worker = null
    }

    /**
     * Anchors θ0 to [mapBearingRad] (the corridor the user says they face), then runs [setup] on
     * the worker so the tracker starts on the same thread that will feed it. [done] gets false,
     * on the main thread, when there is no fresh heading yet (phone face down, sensors warming).
     */
    fun begin(
        mapBearingRad: Double,
        tracker: Tracker,
        setup: (Tracker) -> Unit,
        done: (Boolean) -> Unit,
    ) {
        val h = worker ?: return done(false)
        h.post {
            val aligned = processor.align(mapBearingRad, SystemClock.elapsedRealtimeNanos())
            if (aligned) {
                setup(tracker)
                processor.resetCounts()
                gate.restart()
                openTrace()
                this.tracker = tracker
            }
            main.post { done(aligned) }
        }
    }

    /**
     * Offers a room sign the camera read to the tracker, on the worker thread that owns it
     * (#19), and turns θ0 by the heading fix it measured, so the walk continues facing the
     * right way without another START check. [done] gets the tracker's answer and, if it
     * re-anchored, the new pose, on the main thread; both are null when nothing is tracked.
     */
    fun sign(sighting: SignSighting, done: (SignResult?, PoseEstimate?) -> Unit) {
        val h = worker ?: return done(null, null)
        h.post {
            val t = tracker as? ParticleTracker
            val result = t?.onSighting(sighting)
            // The scan also measured which way the phone points: carry on with that θ0.
            result?.headingFixRad?.let { processor.rotate(it) }
            val pose = if (result?.accepted == true) t.pose else null
            main.post { done(result, pose) }
        }
    }

    /** Stops feeding the tracker (arrival, new destination) but keeps sensors warm. */
    fun pause() {
        worker?.post {
            tracker = null
            closeTrace()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values.copyOf()
        record(event.sensor.type, event.timestamp, values)
        val step = when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                processor.rotation(event.timestamp, values, 0)
                gate.rotation(event.timestamp, values)
                postHeading(event.timestamp)
                null
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gate.acceleration(event.timestamp, values)
                postMoving(event.timestamp)
                processor.acceleration(event.timestamp, values)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gate.gyroscope(event.timestamp, values)
                null
            }
            Sensor.TYPE_STEP_DETECTOR -> processor.detector(event.timestamp)
            else -> null
        } ?: return
        val t = tracker ?: return
        val pose = gate.offer(step).map(t::onMotion).lastOrNull()
        onCandidate?.let { listener ->
            val verdict = gate.lastVerdict?.name.orEmpty()
            val bob = gate.bob(step.timestampNanos)
            main.post { listener(step.timestampNanos, verdict, bob) }
        }
        val particles = t as? ParticleTracker
        val stats = LiveStats(
            steps = processor.steps,
            skipped = processor.skippedSteps,
            usingPeaks = processor.usePeaks,
            calibratedAtNanos = particles?.calibratedAtNanos,
            calibratedStepLengthPx = particles?.calibratedStepLengthPx,
            rejected = gate.rejected,
        )
        if (pose == null) {
            onStats?.let { listener -> main.post { listener(stats) } }
            return
        }
        main.post { onPose(pose, stats) }
    }

    /** Worker thread only: the walk being recorded, if [traceDir] is set. */
    private var trace: TraceCsvWriter? = null

    private fun openTrace() {
        closeTrace()
        val dir = traceDir ?: return
        trace = runCatching {
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            TraceCsvWriter(File(dir, "trace-$stamp.csv").bufferedWriter())
        }.getOrNull()
    }

    private fun closeTrace() {
        runCatching { trace?.close() }
        trace = null
    }

    private fun record(type: Int, timestampNs: Long, values: FloatArray) {
        val out = trace ?: return
        val sensor = when (type) {
            Sensor.TYPE_ACCELEROMETER -> TraceSensor.ACCEL
            Sensor.TYPE_GYROSCOPE -> TraceSensor.GYRO
            Sensor.TYPE_GAME_ROTATION_VECTOR -> TraceSensor.GAME_RV
            Sensor.TYPE_STEP_DETECTOR -> TraceSensor.STEP
            else -> return
        }
        val padded = FloatArray(sensor.components) { values.getOrElse(it) { 0f } }
        // A bad sample must not stop the walk; the trace is diagnostics only.
        runCatching { out.append(timestampNs, sensor, padded) }
    }

    /** Worker thread only. */
    private var lastMovingNs = 0L

    private fun postMoving(timestampNs: Long) {
        val listener = onMoving ?: return
        if (tracker == null || timestampNs - lastMovingNs < MOVING_PERIOD_NS) return
        lastMovingNs = timestampNs
        val moving = gate.moving(timestampNs)
        main.post { listener(moving) }
    }

    /** Worker thread only. */
    private var lastHeadingNs = 0L

    private fun postHeading(timestampNs: Long) {
        val listener = onHeading ?: return
        if (tracker == null || timestampNs - lastHeadingNs < HEADING_PERIOD_NS) return
        val mapRad = processor.heading?.mapRad ?: return
        lastHeadingNs = timestampNs
        main.post { listener(mapRad) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val HEADING_PERIOD_NS = 33_000_000L
        const val MOVING_PERIOD_NS = 100_000_000L
    }
}
