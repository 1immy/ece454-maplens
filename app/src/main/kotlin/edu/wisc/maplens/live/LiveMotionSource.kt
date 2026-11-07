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
import edu.wisc.maplens.tracking.Tracker
import edu.wisc.maplens.tracking.TrackingConfig
import edu.wisc.maplens.tracking.WalkingGate

/** What the banner and session log show about the live sensors after each step. */
data class LiveStats(
    val steps: Long,
    val skipped: Long,
    val usingPeaks: Boolean,
    /** When the tracker last re-calibrated k (#12), and to what; null until it has. */
    val calibratedAtNanos: Long? = null,
    val calibratedStepLengthPx: Double? = null,
    /** Detected steps the walking gate held back as not walking (shaking, waving). */
    val rejected: Long = 0,
)

/**
 * Live sensors → [SensorMotionProcessor] (Johnny's #40) → [WalkingGate] → [Tracker] →
 * [PoseEstimate]. The gate drops detected steps that don't look like walking (shaking the
 * phone in place), so only real walking moves the user.
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
     * Optional: stats after a detected step that did not move the user (held while a walk
     * starts, or rejected as not walking), so a readout can show the gate at work.
     */
    private val onStats: ((LiveStats) -> Unit)? = null,
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
        // The walking gate also watches how fast the phone turns; without one it skips that.
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors.registerListener(this, it, periodUs, h)
        }
        val detector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val detectorOn = detector != null && hasStepPermission &&
            sensors.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, h)
        h.post { processor.selectPeaks(!detectorOn) }
        return true
    }

    fun stop() {
        manager?.unregisterListener(this)
        val t = thread ?: return
        worker?.post { tracker = null }
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
                this.tracker = tracker
            }
            main.post { done(aligned) }
        }
    }

    /** Stops feeding the tracker (arrival, new destination) but keeps sensors warm. */
    fun pause() {
        worker?.post { tracker = null }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values.copyOf()
        val step = when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                processor.rotation(event.timestamp, values, 0)
                gate.rotation(values)
                postHeading(event.timestamp)
                null
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gate.acceleration(event.timestamp, values)
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
        // Steps that don't look like walking never reach the tracker; a walk's first few are
        // held until it is confirmed, then all arrive here at once.
        val walked = gate.offer(step)
        var pose: PoseEstimate? = null
        for (s in walked) pose = t.onMotion(s)
        val particles = t as? ParticleTracker
        val stats = LiveStats(
            steps = processor.steps,
            skipped = processor.skippedSteps,
            usingPeaks = processor.usePeaks,
            calibratedAtNanos = particles?.calibratedAtNanos,
            calibratedStepLengthPx = particles?.calibratedStepLengthPx,
            rejected = gate.rejected,
        )
        val latest = pose
        if (latest == null) {
            onStats?.let { listener -> main.post { listener(stats) } }
            return
        }
        main.post { onPose(latest, stats) }
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
    }
}
