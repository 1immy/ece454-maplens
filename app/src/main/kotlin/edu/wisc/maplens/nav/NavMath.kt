package edu.wisc.maplens.nav

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Small, Android-free helpers for the navigation screen, so they unit-test on the JVM. */
internal object NavMath {
    /** Typical indoor walking speed, used only for the displayed time. */
    const val WALK_SPEED_MPS = 1.2

    /** Wraps an angle into (-π, π]. */
    fun wrap(angle: Double): Double = atan2(sin(angle), cos(angle))

    /** Moves [from] toward [to] by fraction [t] along the shorter way round. */
    fun lerpAngle(from: Double, to: Double, t: Double): Double = wrap(from + wrap(to - from) * t)

    /**
     * Fraction of the gap to close this frame for exponential smoothing with time constant
     * [tauSeconds]: the same feel at 60 or 120 Hz. A time constant of 0 snaps.
     */
    fun approach(dtSeconds: Double, tauSeconds: Double): Double =
        if (tauSeconds <= 0.0) 1.0 else 1.0 - exp(-dtSeconds / tauSeconds)

    /** Distance as people say it: exact up to 20 m, then to 5 m, then to 10 m. */
    fun roundedMeters(meters: Double): Int {
        val m = meters.coerceAtLeast(0.0)
        return when {
            m < 20 -> m.roundToInt()
            m < 100 -> (m / 5).roundToInt() * 5
            else -> (m / 10).roundToInt() * 10
        }
    }

    /** Whole minutes to walk [meters], never less than 1 while there is any way to go. */
    fun walkingMinutes(meters: Double): Int =
        if (meters <= 0) 0 else ceil(meters / WALK_SPEED_MPS / 60).toInt().coerceAtLeast(1)

    /** Degrees for a screen rotation that turns map bearing [upRad] to point up the screen. */
    fun screenRotationDeg(upRad: Double): Double = Math.toDegrees(wrap(-PI / 2 - upRad))
}
