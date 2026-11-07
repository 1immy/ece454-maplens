package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.MotionEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/** Why a step was or wasn't passed on, for logs and the developer readout. */
enum class GaitVerdict {
    WALKING,
    INSUFFICIENT_DATA,
    TOO_MUCH_TURN,
    TOO_LITTLE_BOB,
    TOO_SIDEWAYS,
    VERTICAL_SHAKE,
    ISOLATED_BUMP,
    TOO_SOON,

    /** Looks like a footfall, held until enough others follow in a walking rhythm. */
    AWAITING_RHYTHM,
}

/**
 * Treats a detected step as a candidate and checks the motion around its footfall.
 *
 * Step detectors fire on any bump that looks like a heel strike, so shaking the phone in
 * place "walks" the user across the map. Each step is checked against the sensor window
 * before its footfall:
 * Rotation, world-frame acceleration and a vertical rise/fall must agree with the candidate.
 * It is accepted or rejected immediately from past samples, including the first footfall.
 * Missing motion evidence rejects the candidate.
 *
 * A single swing or tap of a phone held still can pass every check, so one footfall on
 * its own never moves the user. Walking is a run of footfalls about half a second apart: a
 * walk starts once [TrackingConfig.gaitRhythmFootfalls] footfalls arrive no more than
 * [TrackingConfig.gaitRhythmStartGapNs] apart, and then all of them pass at once. While
 * the walk lasts, each footfall within [TrackingConfig.gaitRhythmKeepGapNs] of the last
 * passes immediately. A footfall that fails the checks breaks a run not yet started.
 * One sensor worker owns this class.
 */
class WalkingGate(private val config: TrackingConfig = TrackingConfig()) {
    private class MotionSample(
        val timeNs: Long,
        val vertical: Double,
        val horizontal: Double,
        val smoothedVertical: Double,
    )

    private class TurnSample(val timeNs: Long, val squaredRate: Double)

    /** World-frame linear acceleration: vertical, and horizontal magnitude. */
    private val motion = ArrayDeque<MotionSample>()

    /** Gyroscope: squared rotation rate. */
    private val turn = ArrayDeque<TurnSample>()
    private var quaternion: DoubleArray? = null
    private var rotationNs = 0L
    private var lastAcceptedNs: Long? = null

    /** Footfalls that passed the checks while no walk is under way, waiting for a rhythm. */
    private val held = ArrayList<MotionEvent.Step>()

    /** Steps rejected since the last reset, for the session log and readout. */
    var rejected = 0L
        private set
    var lastVerdict: GaitVerdict? = null
        private set
    fun reset() {
        motion.clear()
        turn.clear()
        quaternion = null
        restart()
    }

    /** New navigation: reset footfall bookkeeping and keep the warm sensor history. */
    fun restart() {
        lastAcceptedNs = null
        held.clear()
        rejected = 0
        lastVerdict = null
    }

    /** Game-rotation-vector sample (x, y, z[, w]); turns later accelerometer samples upright. */
    fun rotation(timestampNs: Long, vector: FloatArray) {
        if (timestampNs < rotationNs && quaternion != null) return
        if (vector.size < 3 || vector.any { !it.isFinite() }) return
        val x = vector[0].toDouble()
        val y = vector[1].toDouble()
        val z = vector[2].toDouble()
        val w = vector.getOrNull(3)?.toDouble()
            ?: sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n !in 0.5..1.5) return
        quaternion = doubleArrayOf(x / n, y / n, z / n, w / n)
        rotationNs = timestampNs
    }

    /** Accelerometer sample (m/s², with gravity), in device axes. */
    fun acceleration(timestampNs: Long, values: FloatArray) {
        val q = quaternion ?: return
        val freshness = config.gaitFreshnessNs
        if (timestampNs - rotationNs !in -freshness..freshness) return
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
        val previous = motion.lastOrNull()
        val deltaNs = previous?.let { timestampNs - it.timeNs }
        if (deltaNs != null && deltaNs <= 0) return
        val smoothed = if (previous != null && deltaNs != null && deltaNs <= freshness) {
            val alpha = 1 - exp(-deltaNs / 1e9 / config.gaitVerticalSmoothingSeconds)
            previous.smoothedVertical + alpha * (wz - previous.smoothedVertical)
        } else {
            wz
        }
        motion.addLast(MotionSample(timestampNs, wz, hypot(wx, wy), smoothed))
        while (timestampNs - motion.first().timeNs > HISTORY_NS) motion.removeFirst()
    }

    /**
     * Gyroscope sample (rad/s), device axes. Only rotation about horizontal axes counts: a
     * walker turning a corner or turning round spins the phone about the vertical, which is
     * walking, while shaking or flicking the phone tips it about horizontal axes. With no
     * fresh attitude yet, the whole rotation rate counts.
     */
    fun gyroscope(timestampNs: Long, values: FloatArray) {
        if (values.size < 3 || values.any { !it.isFinite() }) return
        val gx = values[0].toDouble()
        val gy = values[1].toDouble()
        val gz = values[2].toDouble()
        val q = quaternion
        val r2 = if (q != null && abs(timestampNs - rotationNs) <= config.gaitFreshnessNs) {
            val (x, y, z, w) = q
            // World = R·device; drop the vertical (z) component, keep the tipping rate.
            val wx = (1 - 2 * (y * y + z * z)) * gx + 2 * (x * y - z * w) * gy +
                2 * (x * z + y * w) * gz
            val wy = 2 * (x * y + z * w) * gx + (1 - 2 * (x * x + z * z)) * gy +
                2 * (y * z - x * w) * gz
            wx * wx + wy * wy
        } else {
            gx * gx + gy * gy + gz * gz
        }
        if (turn.lastOrNull()?.let { timestampNs <= it.timeNs } == true) return
        turn.addLast(TurnSample(timestampNs, r2))
        while (timestampNs - turn.first().timeNs > HISTORY_NS) turn.removeFirst()
    }

    /**
     * How much the phone is bobbing up and down right now: RMS of the world-frame vertical
     * acceleration over the last [windowNs] before [nowNs], or null without enough samples.
     * A walker bobs with every step; someone standing still doesn't, so this drops within a
     * fraction of a second of stopping, long before "no next step" could tell.
     */
    fun bob(nowNs: Long, windowNs: Long = config.gaitStillWindowNs): Double? {
        val recent = motion.filter { it.timeNs in (nowNs - windowNs + 1)..nowNs }
        if (recent.size < MIN_SAMPLES) return null
        val mean = recent.sumOf { it.vertical } / recent.size
        return sqrt(recent.sumOf { (it.vertical - mean) * (it.vertical - mean) } / recent.size)
    }

    /** Whether the phone still moves like a walker's; true when there is no evidence either way. */
    fun moving(
        nowNs: Long
    ): Boolean = (bob(nowNs) ?: Double.MAX_VALUE) >= config.gaitStillVerticalRms

    /**
     * The footfalls this candidate lets through: itself during a walk, itself and the held
     * ones when it completes a walking rhythm, or none.
     */
    fun offer(step: MotionEvent.Step): List<MotionEvent.Step> {
        if (!config.gaitGateEnabled) return listOf(step)
        val t = step.timestampNanos
        val previous = held.lastOrNull()?.timestampNanos ?: lastAcceptedNs
        val tooSoon = previous?.let { t - it < config.gaitMinIntervalNs } == true
        val verdict = if (tooSoon) GaitVerdict.TOO_SOON else check(t)
        if (verdict != null) {
            rejected++
            lastVerdict = verdict
            // Walkers start with a clean run of footfalls; one that fails breaks the run.
            if (verdict != GaitVerdict.TOO_SOON) dropHeld()
            return emptyList()
        }
        val walking = lastAcceptedNs?.let { t - it <= config.gaitRhythmKeepGapNs } == true
        if (walking) return accept(listOf(step))
        if (held.isNotEmpty() && t - held.last().timestampNanos > config.gaitRhythmStartGapNs) {
            dropHeld()
        }
        held += step
        if (held.size < config.gaitRhythmFootfalls) {
            lastVerdict = GaitVerdict.AWAITING_RHYTHM
            return emptyList()
        }
        val run = held.toList()
        held.clear()
        return accept(run)
    }

    private fun dropHeld() {
        rejected += held.size
        held.clear()
    }

    private fun accept(steps: List<MotionEvent.Step>): List<MotionEvent.Step> {
        lastAcceptedNs = steps.last().timestampNanos
        lastVerdict = GaitVerdict.WALKING
        return steps
    }

    /** The first check this footfall's window fails, or null if it looks like walking. */
    private fun check(t: Long): GaitVerdict? {
        val from = t - config.gaitWindowNs
        val spin = turn.filter { it.timeNs in (from + 1)..t }
        val moved = motion.filter { it.timeNs in (from + 1)..t }
        if (spin.size < MIN_SAMPLES || moved.size < MIN_SAMPLES ||
            t - spin.last().timeNs > config.gaitFreshnessNs ||
            t - moved.last().timeNs > config.gaitFreshnessNs
        ) {
            return GaitVerdict.INSUFFICIENT_DATA
        }
        val rotationRms = sqrt(spin.sumOf { it.squaredRate } / spin.size)
        if (rotationRms > config.gaitMaxGyroRms) return GaitVerdict.TOO_MUCH_TURN
        val mean = moved.sumOf { it.vertical } / moved.size
        val vertical =
            sqrt(moved.sumOf { (it.vertical - mean) * (it.vertical - mean) } / moved.size)
        val horizontal = sqrt(moved.sumOf { it.horizontal * it.horizontal } / moved.size)
        val quiet = rotationRms <= config.gaitQuietMaxGyroRms &&
            horizontal <= config.gaitQuietMaxHorizontalRms
        val minVertical = if (quiet) config.gaitMinVerticalRms else config.gaitLightVerticalRms
        if (vertical < minVertical) return GaitVerdict.TOO_LITTLE_BOB
        if (vertical > config.gaitMaxVerticalRms) return GaitVerdict.VERTICAL_SHAKE
        val verticalShare = if (quiet) {
            config.gaitQuietMinVerticalShare
        } else {
            config.gaitMinVerticalShare
        }
        if (horizontal > config.gaitMaxHorizontalRms ||
            vertical < verticalShare * horizontal
        ) {
            return GaitVerdict.TOO_SIDEWAYS
        }
        val excursion = if (quiet && vertical < config.gaitLightVerticalRms) {
            config.gaitLightVerticalExcursion
        } else {
            config.gaitVerticalExcursion
        }
        val reversals = verticalReversals(moved, excursion)
        if (reversals == 0) return GaitVerdict.ISOLATED_BUMP
        if (reversals > config.gaitMaxVerticalReversals) return GaitVerdict.VERTICAL_SHAKE
        return null
    }

    private fun verticalReversals(samples: List<MotionSample>, excursion: Double): Int {
        var direction = 0
        var reversals = 0
        for (sample in samples) {
            val acceleration = sample.smoothedVertical - GRAVITY
            val next = when {
                acceleration > excursion -> 1
                acceleration < -excursion -> -1
                else -> continue
            }
            if (direction != 0 && next != direction) reversals++
            direction = next
        }
        return reversals
    }

    private companion object {
        const val MIN_SAMPLES = 8
        const val GRAVITY = 9.80665

        /** Step-detector events can arrive this late; keep the window they need. */
        const val HISTORY_NS = 3_000_000_000L
    }
}
