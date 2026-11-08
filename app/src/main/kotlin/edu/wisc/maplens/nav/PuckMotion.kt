package edu.wisc.maplens.nav

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Where to draw the user along the route, every frame, while they walk.
 *
 * The tracker only learns the position at each footfall, about every 0.6 s and a little late,
 * so drawing those positions makes the dot stop and start or jump. Maps apps instead keep the
 * dot moving between fixes, snapped to the route, and fold each new fix in gently (Mapbox's
 * "keyPoints" and matched route puck). This does the same along the route's arc length:
 *
 * - **Steady motion:** while the user walks, the dot advances at walking speed, cadence
 *   (from the footfall intervals) times step length, so it moves every frame, not per step.
 * - **No jumps:** a new tracker position only adjusts that speed. The dot speeds up a little
 *   to catch up when behind and slows when ahead, closing the gap over [catchUpSeconds]. It
 *   never moves backwards; only a large correction (past [snapPx], e.g. a re-anchor) slides it
 *   over quickly.
 * - **Never far ahead:** between footfalls it predicts at most one step past the last fix,
 *   so if the steps stop it can't run away. Running ahead is corrected harder than falling
 *   behind ([aheadSeconds]), so it never builds up.
 * - **No going back while walking:** only once the user has stopped does the dot ease back
 *   the last bit (at most a step) onto where the tracker last put them, over [settleSeconds],
 *   so it rests exactly there instead of a step past it.
 * - **Smooth start:** a walk is only confirmed after a few footfalls (see `WalkingGate`), so
 *   the first fix of a walk lands a few steps ahead. The dot walks off that gap at up to
 *   [startSpeedFactor] times walking speed instead of sliding, unless it is past twice [snapPx].
 * - **Quick stop:** when the phone stops bobbing ([setMoving] false) or no footfall comes for
 *   a while, the speed eases to zero in about [stopSeconds].
 *
 * Pure Kotlin on a simple clock in seconds, so it unit-tests without a phone.
 */
class PuckMotion(
    /** Corrections larger than this (route px) slide over quickly instead of being walked off. */
    private val snapPx: Double,
    private val catchUpSeconds: Double = 0.7,
    /** Closes a lead faster than a lag, so small leads never add up over a walk. */
    private val aheadSeconds: Double = 0.3,
    private val settleSeconds: Double = 0.5,
    private val stopSeconds: Double = 0.25,
    /** Fastest the dot may go, as a multiple of walking speed. */
    private val maxSpeedFactor: Double = 1.8,
    /** Fastest the dot may go while catching up with the start of a walk. */
    private val startSpeedFactor: Double = 2.5,
    /** Cadence before the first interval is known, steps per second. */
    private val defaultCadence: Double = 1.7,
) {
    /** Distance along the route of the drawn dot. */
    var s = 0.0
        private set

    /** Current speed of the drawn dot, route px per second. */
    var speed = 0.0
        private set

    private var target = 0.0
    private var lastStepAt = Double.NEGATIVE_INFINITY
    private var lastFootfallNs: Long? = null
    private var cadence = defaultCadence
    private var stepPx = 0.0
    private var moving = true
    private var clock: Double? = null
    private var started = false
    private var sliding = false

    /** Catching up with a walk that started from standing. */
    private var starting = false

    /** Starts at [atPx] along the route, standing still. */
    fun reset(atPx: Double) {
        s = atPx
        target = atPx
        speed = 0.0
        lastStepAt = Double.NEGATIVE_INFINITY
        lastFootfallNs = null
        cadence = defaultCadence
        clock = null
        started = false
        sliding = false
        starting = false
        moving = true
    }

    /**
     * A footfall moved the tracker to [trackerPx] along the route. [now] is when it reached
     * the display; [footfallNs] its sensor time, whose spacing gives the cadence.
     */
    fun onStep(trackerPx: Double, now: Double, stepLengthPx: Double, footfallNs: Long) {
        lastFootfallNs?.let { previous ->
            val interval = (footfallNs - previous) / 1e9
            if (interval in MIN_INTERVAL..MAX_INTERVAL) {
                cadence += CADENCE_BLEND * (1.0 / interval - cadence)
            }
        }
        if (!isWalking(now)) starting = true
        lastFootfallNs = footfallNs
        stepPx = stepLengthPx
        target = trackerPx
        lastStepAt = now
        started = true
    }

    /** Whether the phone still moves like a walker's (from the motion sensors). */
    fun setMoving(isMoving: Boolean) {
        moving = isMoving
    }

    /** Whether the dot is still moving, so the view keeps drawing frames. */
    val animating: Boolean get() = abs(speed) > 0.5 || abs(predicted(clock ?: 0.0) - s) > 0.5

    /** Advances the dot to time [now] (seconds) and returns its distance along the route. */
    fun advance(now: Double): Double {
        val last = clock ?: now
        val dt = (now - last).coerceIn(0.0, MAX_FRAME)
        clock = now
        val walking = isWalking(now)
        val walkSpeed = if (walking) cadence * stepPx else 0.0
        val error = predicted(now) - s
        // Re-anchor or a big correction: slide over briskly rather than crawl or jump, until
        // the gap is down to half a step.
        if (starting && (!walking || error < SLIDE_DONE * maxOf(stepPx, 1.0))) starting = false
        if (abs(error) > if (starting) 2 * snapPx else snapPx) sliding = true
        if (sliding && abs(error) < SLIDE_DONE * maxOf(stepPx, 1.0)) sliding = false
        if (sliding) {
            s += error * approach(dt, SNAP_SECONDS)
            speed = if (walking) walkSpeed else 0.0
            return s
        }
        val wanted = when {
            walking -> walkSpeed + error / if (error < 0) aheadSeconds else catchUpSeconds
            // Stopped: finish the distance the tracker confirmed, or ease back onto it.
            error > 0 -> error / stopSeconds
            else -> error / settleSeconds
        }
        val factor = if (starting) startSpeedFactor else maxSpeedFactor
        val cap = factor * maxOf(walkSpeed, cadence * stepPx)
        val floor = if (walking) 0.0 else -cap
        val next = wanted.coerceIn(floor, cap)
        speed += (next - speed) * approach(dt, if (next < speed) stopSeconds / 2 else SPEED_SECONDS)
        if (walking && speed < 0) speed = 0.0
        s += speed * dt
        return s
    }

    /** Where the walker most likely is now: the last fix, plus at most one step since. */
    private fun predicted(now: Double): Double {
        if (!isWalking(now)) return target
        val since = (now - lastStepAt).coerceAtLeast(0.0)
        return target + min(since * cadence, LEAD_STEPS) * stepPx
    }

    private fun isWalking(now: Double): Boolean {
        if (!started || !moving) return false
        // A footfall is due every 1/cadence. The phone still bobs (moving), so allow a couple
        // of missed ones before calling it a stop; a real stop ends the bob within ~0.5 s.
        return now - lastStepAt < maxOf(STEP_GRACE / cadence, MIN_GRACE)
    }

    private fun approach(dt: Double, tau: Double) = if (tau <= 0) 1.0 else 1 - exp(-dt / tau)

    private companion object {
        const val MIN_INTERVAL = 0.3
        const val MAX_INTERVAL = 1.4
        const val CADENCE_BLEND = 0.35
        const val MAX_FRAME = 0.1
        const val SNAP_SECONDS = 0.18
        const val SLIDE_DONE = 0.5
        const val LEAD_STEPS = 1.0
        const val SPEED_SECONDS = 0.12
        const val STEP_GRACE = 3.0
        const val MIN_GRACE = 1.5
    }
}
