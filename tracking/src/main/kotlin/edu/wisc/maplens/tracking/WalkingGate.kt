package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.MotionEvent
import kotlin.math.hypot
import kotlin.math.sqrt

/** Why a step was or wasn't passed on, for logs and the developer readout. */
enum class GaitVerdict { WALKING, STARTING, TOO_MUCH_TURN, TOO_LITTLE_BOB, TOO_SIDEWAYS }

/**
 * Lets a detected step move the user only when the phone is actually walking (#12).
 *
 * Step detectors fire on any bump that looks like a heel strike, so shaking the phone in
 * place "walks" the user across the map. Each step is checked against the sensor window
 * before its footfall:
 * - **Turning:** held in front while walking, the phone barely rotates; shaking or waving
 *   it turns it fast ([TrackingConfig.gaitMaxGyroRms]).
 * - **Bob:** walking moves the phone up and down with each step. The accelerometer is turned
 *   into the world frame with the game rotation vector, so "up" is up however the phone is
 *   held ([TrackingConfig.gaitMinVerticalRms]).
 * - **Direction:** that motion is mostly vertical while walking and mostly sideways while
 *   shaking ([TrackingConfig.gaitMinVerticalShare], [TrackingConfig.gaitMaxHorizontalRms]).
 *
 * On top of that, a walk only starts after [TrackingConfig.gaitStartSteps] good steps in a
 * steady rhythm. Those steps are held back meanwhile and released together, each with the
 * heading it was taken at, so a real walk loses nothing but a single bump or gesture never
 * moves the user. A pause of [TrackingConfig.gaitStopGapNs] or
 * [TrackingConfig.gaitStopRejects] rejected steps in a row ends the walk.
 *
 * A sensor that isn't there (no gyroscope, no rotation vector yet) skips its check rather
 * than blocking every step. Android-free so it unit-tests and replays traces on the JVM;
 * not thread safe, one sensor worker owns it.
 */
class WalkingGate(private val config: TrackingConfig = TrackingConfig()) {
    private class Sample(val t: Long, val a: Double, val b: Double)

    /** World-frame linear acceleration: vertical, and horizontal magnitude. */
    private val motion = ArrayDeque<Sample>()

    /** Gyroscope: squared rotation rate. */
    private val turn = ArrayDeque<Sample>()
    private var quaternion: DoubleArray? = null

    private val held = mutableListOf<MotionEvent.Step>()
    private var walking = false
    private var lastStepNs = 0L
    private var rejectsInRow = 0

    /** Steps rejected since the last reset, for the session log and readout. */
    var rejected = 0L
        private set
    var lastVerdict: GaitVerdict? = null
        private set
    val isWalking: Boolean get() = walking

    fun reset() {
        motion.clear()
        turn.clear()
        quaternion = null
        held.clear()
        walking = false
        lastStepNs = 0L
        rejectsInRow = 0
        rejected = 0
        lastVerdict = null
    }

    /** New navigation: forget any walk in progress and the counts, keep the sensor history. */
    fun restart() {
        held.clear()
        walking = false
        lastStepNs = 0L
        rejectsInRow = 0
        rejected = 0
        lastVerdict = null
    }

    /** Game-rotation-vector sample (x, y, z[, w]); turns later accelerometer samples upright. */
    fun rotation(vector: FloatArray) {
        if (vector.size < 3 || vector.any { !it.isFinite() }) return
        val x = vector[0].toDouble()
        val y = vector[1].toDouble()
        val z = vector[2].toDouble()
        val w = vector.getOrNull(3)?.toDouble()
            ?: sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 0.5) return
        quaternion = doubleArrayOf(x / n, y / n, z / n, w / n)
    }

    /** Accelerometer sample (m/s², with gravity), in device axes. */
    fun acceleration(timestampNs: Long, values: FloatArray) {
        val q = quaternion ?: return
        if (values.size < 3 || values.any { !it.isFinite() }) return
        val (x, y, z, w) = q
        val ax = values[0].toDouble()
        val ay = values[1].toDouble()
        val az = values[2].toDouble()
        // World = R·device, with R from the unit quaternion; world z points up.
        val wx = (1 - 2 * (y * y + z * z)) * ax + 2 * (x * y - z * w) * ay +
            2 * (x * z + y * w) * az
        val wy = 2 * (x * y + z * w) * ax + (1 - 2 * (x * x + z * z)) * ay +
            2 * (y * z - x * w) * az
        val wz = 2 * (x * z - y * w) * ax + 2 * (y * z + x * w) * ay +
            (1 - 2 * (x * x + y * y)) * az
        motion.addLast(Sample(timestampNs, wz, hypot(wx, wy)))
        trim(motion, timestampNs)
    }

    /** Gyroscope sample (rad/s), device axes. */
    fun gyroscope(timestampNs: Long, values: FloatArray) {
        if (values.size < 3 || values.any { !it.isFinite() }) return
        val r2 = values.take(3).sumOf { it.toDouble() * it }
        turn.addLast(Sample(timestampNs, r2, 0.0))
        trim(turn, timestampNs)
    }

    /**
     * Offers one detected step. Returns the steps that should move the user now: none while
     * a walk is starting or the step doesn't look like walking, all held steps once a walk is
     * confirmed, and just this one during a walk.
     */
    fun offer(step: MotionEvent.Step): List<MotionEvent.Step> {
        if (!config.gaitGateEnabled) return listOf(step)
        val t = step.timestampNanos
        val gap = if (lastStepNs == 0L) Long.MAX_VALUE else t - lastStepNs
        lastStepNs = t
        if (walking && gap > config.gaitStopGapNs) stop()

        val check = check(t)
        if (check != null) {
            rejected++
            rejectsInRow++
            lastVerdict = check
            if (walking && rejectsInRow >= config.gaitStopRejects) stop()
            if (!walking) held.clear()
            return emptyList()
        }
        rejectsInRow = 0
        if (walking) {
            lastVerdict = GaitVerdict.WALKING
            return listOf(step)
        }
        // Starting: the held steps must keep one steady rhythm, or the run starts over here.
        if (held.isNotEmpty() && gap !in config.gaitMinIntervalNs..config.gaitMaxIntervalNs) {
            rejected += held.size
            held.clear()
        }
        held += step
        if (held.size < config.gaitStartSteps) {
            lastVerdict = GaitVerdict.STARTING
            return emptyList()
        }
        walking = true
        lastVerdict = GaitVerdict.WALKING
        return held.toList().also { held.clear() }
    }

    /** The first check this footfall's window fails, or null if it looks like walking. */
    private fun check(t: Long): GaitVerdict? {
        val from = t - config.gaitWindowNs
        val spin = turn.filter { it.t in (from + 1)..t }
        if (spin.size >= MIN_SAMPLES) {
            val rms = sqrt(spin.sumOf { it.a } / spin.size)
            if (rms > config.gaitMaxGyroRms) return GaitVerdict.TOO_MUCH_TURN
        }
        val moved = motion.filter { it.t in (from + 1)..t }
        if (moved.size >= MIN_SAMPLES) {
            val mean = moved.sumOf { it.a } / moved.size
            val vertical = sqrt(moved.sumOf { (it.a - mean) * (it.a - mean) } / moved.size)
            val horizontal = sqrt(moved.sumOf { it.b * it.b } / moved.size)
            if (vertical < config.gaitMinVerticalRms) return GaitVerdict.TOO_LITTLE_BOB
            if (horizontal > config.gaitMaxHorizontalRms ||
                vertical < config.gaitMinVerticalShare * horizontal
            ) {
                return GaitVerdict.TOO_SIDEWAYS
            }
        }
        return null
    }

    private fun stop() {
        walking = false
        rejected += held.size
        held.clear()
        rejectsInRow = 0
    }

    private fun trim(samples: ArrayDeque<Sample>, now: Long) {
        while (samples.isNotEmpty() && now - samples.first().t > HISTORY_NS) samples.removeFirst()
    }

    private companion object {
        /** Fewer samples than this in a window (sensor missing or just started): skip the check. */
        const val MIN_SAMPLES = 8

        /** Step-detector events can arrive this late; keep the window they need. */
        const val HISTORY_NS = 3_000_000_000L
    }
}
