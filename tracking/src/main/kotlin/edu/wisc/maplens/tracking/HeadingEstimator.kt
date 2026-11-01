package edu.wisc.maplens.tracking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wraps any angle into (-π, π]. All headings here are clockwise radians: map angles use
 * atan2(dy, dx) in the map's y-down image frame, so 0 is map-right and π/2 is map-down.
 * Always compare or subtract headings through this function, never raw, so 359° vs 1° is 2°.
 */
fun wrapHeading(angle: Double): Double = atan2(sin(angle), cos(angle))

/**
 * One heading sample.
 *
 * @property relativeRad heading in the game-rotation-vector world frame, whose zero is an
 *   arbitrary direction fixed when the sensor started (there is no magnetometer, so no north).
 * @property mapRad the same heading in map coordinates, or null until [HeadingEstimator.align]
 *   has supplied the offset between the two frames.
 */
data class HeadingReading(val timestampNs: Long, val relativeRad: Double, val mapRad: Double?)

/**
 * Turns game-rotation-vector quaternions into the direction the user is facing (#12).
 *
 * Android-free, so it is unit tested on the JVM. It projects the quaternion directly instead of
 * using Euler angles, which avoids gimbal lock when the phone is held upright, and it never reads
 * the magnetometer, which steel and wiring distort indoors.
 *
 * "Facing" is `f = R·ŷ − R·ẑ` projected onto the floor (`project-AI-context.md`, A2): ŷ is screen
 * up and −ẑ points out of the back of the phone. Held flat, ẑ is vertical, so f is the top edge;
 * held upright, ŷ is vertical, so f is where the camera points. Their sum stays well defined
 * through every tilt in between, so one formula covers the whole carry mode.
 *
 * The map heading is `relative + θ0`, where θ0 ([offsetRad]) comes from [align] while the user
 * faces a known corridor. Not thread safe: one sensor worker owns each instance.
 */
class HeadingEstimator(private val config: TrackingConfig = TrackingConfig()) {
    /** Last accepted sample: the smoothing state and the reference [align] uses. */
    private var previous: HeadingReading? = null

    /** Display rotation of [previous]; a change restarts smoothing instead of blending. */
    private var rotation = 0

    /** θ0, map bearing minus relative heading; null until the user aligns. */
    var offsetRad: Double? = null
        private set

    fun reset() {
        previous = null
        offsetRad = null
    }

    /**
     * Records that the user is now facing [mapBearingRad] (for example "map up" = −π/2).
     * Returns false if there is no current heading to anchor to, e.g. the phone is face down.
     */
    fun align(mapBearingRad: Double): Boolean {
        require(mapBearingRad.isFinite())
        val reading = previous ?: return false
        offsetRad = wrapHeading(mapBearingRad - reading.relativeRad)
        return true
    }

    /**
     * Folds in one game-rotation-vector event and returns the smoothed heading, or null when the
     * sample is invalid, out of order, or the pose has no meaningful facing direction.
     *
     * @param vector Android's rotation vector (x, y, z[, w]); w is reconstructed if absent.
     * @param screenRotation `Surface.ROTATION_0..3`, so "screen up" follows the displayed UI
     *   in landscape instead of the device's physical +y axis.
     */
    fun update(timestampNs: Long, vector: FloatArray, screenRotation: Int = 0): HeadingReading? {
        if (timestampNs < 0 || vector.size < 3 || vector.any { !it.isFinite() }) return null
        if (previous?.let { timestampNs <= it.timestampNs } == true) return null
        val x0 = vector[0].toDouble()
        val y0 = vector[1].toDouble()
        val z0 = vector[2].toDouble()
        // Some devices send only x, y, z; Android defines w as the positive root of a unit quaternion.
        val w0 = vector.getOrNull(3)?.toDouble()
            ?: sqrt((1 - x0 * x0 - y0 * y0 - z0 * z0).coerceAtLeast(0.0))
        // Reject garbage rather than normalise it; small float drift is normalised away below.
        val length = sqrt(x0 * x0 + y0 * y0 + z0 * z0 + w0 * w0)
        if (length < 0.5 || length > 1.5) return null
        val x = x0 / length
        val y = y0 / length
        val z = z0 / length
        val w = w0 / length
        // Horizontal (world x, y) parts of device axes from the quaternion's rotation matrix R:
        //   R·x̂ = (1 − 2(y² + z²), 2(xy + zw)),  R·ŷ = (2(xy − zw), 1 − 2(x² + z²)).
        // Screen up is device +y, −x, −y, +x for display rotations 0, 1, 2, 3.
        val up = when (screenRotation) {
            0 -> doubleArrayOf(2 * (x * y - z * w), 1 - 2 * (x * x + z * z))
            1 -> doubleArrayOf(-(1 - 2 * (y * y + z * z)), -2 * (x * y + z * w))
            2 -> doubleArrayOf(-2 * (x * y - z * w), -(1 - 2 * (x * x + z * z)))
            3 -> doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y + z * w))
            else -> error("Display rotation must be 0..3")
        }
        // f = screen up − R·ẑ, where R·ẑ = (2(xz + yw), 2(yz − xw), 1 − 2(x² + y²)).
        val fx = up[0] - 2 * (x * z + y * w)
        val fy = up[1] - 2 * (y * z - x * w)
        // The z of R·ẑ drops below −headingMinProjection when the screen faces the floor, and a
        // short f means the two terms cancelled; either way "facing" is undefined. Drop the
        // smoothing state too, so a later sample can't blend with a pre-flip heading.
        if (1 - 2 * (x * x + y * y) < -config.headingMinProjection ||
            hypot(fx, fy) < config.headingMinProjection
        ) {
            previous = null
            return null
        }
        // atan2(fx, fy) measures from world +y towards +x: clockwise seen from above, the same
        // sense as the y-down map, so a single additive offset converts between the two.
        val raw = atan2(fx, fy)
        val old = previous
        val dt = old?.let { (timestampNs - it.timestampNs) / 1e9 }
        // Exponential smoothing with time constant headingSmoothingSeconds, so it behaves the
        // same at any sample rate. Blending the wrapped difference keeps 179° → −179° a 2° step.
        // Restart after a gap or a display rotation, where the old value no longer applies.
        val smoothed = if (old == null || dt == null ||
            timestampNs - old.timestampNs > config.headingFreshnessNs || rotation != screenRotation
        ) {
            raw
        } else {
            wrapHeading(
                old.relativeRad + (1 - exp(-dt / config.headingSmoothingSeconds)) *
                    wrapHeading(raw - old.relativeRad)
            )
        }
        rotation = screenRotation
        return HeadingReading(timestampNs, smoothed, offsetRad?.let { wrapHeading(smoothed + it) })
            .also { previous = it }
    }
}
