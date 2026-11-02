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
import edu.wisc.maplens.tracking.SensorMotionProcessor
import edu.wisc.maplens.tracking.Tracker
import edu.wisc.maplens.tracking.TrackingConfig

/** What the banner shows about the live sensors after each step. */
data class LiveStats(val steps: Long, val skipped: Long, val usingPeaks: Boolean)

/**
 * Live sensors → [SensorMotionProcessor] (Johnny's #40) → [Tracker] → [PoseEstimate].
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
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(SensorManager::class.java)
    private val periodUs = config.recordingPeriodUs
    private val processor = SensorMotionProcessor(config)
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
        h.post { processor.reset() }
        sensors.registerListener(this, rotation, periodUs, h)
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
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
        val step = when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                processor.rotation(event.timestamp, event.values.copyOf(), 0)
                null
            }
            Sensor.TYPE_ACCELEROMETER ->
                processor.acceleration(event.timestamp, event.values.copyOf())
            Sensor.TYPE_STEP_DETECTOR -> processor.detector(event.timestamp)
            else -> null
        } ?: return
        val t = tracker ?: return
        val pose = t.onMotion(step)
        val stats = LiveStats(processor.steps, processor.skippedSteps, processor.usePeaks)
        main.post { onPose(pose, stats) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
