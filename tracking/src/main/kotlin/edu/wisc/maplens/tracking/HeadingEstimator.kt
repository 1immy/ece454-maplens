package edu.wisc.maplens.tracking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Clockwise radians. Map angles use atan2(dy, dx) in the map's y-down frame. */
fun wrapHeading(angle: Double): Double = atan2(sin(angle), cos(angle))

data class HeadingReading(val timestampNs: Long, val relativeRad: Double, val mapRad: Double?)

/** Android-free quaternion projection; no magnetic north or Euler-angle singularities. */
class HeadingEstimator(private val config: TrackingConfig = TrackingConfig()) {
    private var previous: HeadingReading? = null
    private var rotation = 0
    var offsetRad: Double? = null
        private set

    fun reset() {
        previous = null
        offsetRad = null
    }

    fun align(mapBearingRad: Double): Boolean {
        require(mapBearingRad.isFinite())
        val reading = previous ?: return false
        offsetRad = wrapHeading(mapBearingRad - reading.relativeRad)
        return true
    }

    fun update(timestampNs: Long, vector: FloatArray, screenRotation: Int = 0): HeadingReading? {
        if (timestampNs < 0 || vector.size < 3 || vector.any { !it.isFinite() }) return null
        if (previous?.let { timestampNs <= it.timestampNs } == true) return null
        val x0 = vector[0].toDouble()
        val y0 = vector[1].toDouble()
        val z0 = vector[2].toDouble()
        val w0 = vector.getOrNull(3)?.toDouble()
            ?: sqrt((1 - x0 * x0 - y0 * y0 - z0 * z0).coerceAtLeast(0.0))
        val length = sqrt(x0 * x0 + y0 * y0 + z0 * z0 + w0 * w0)
        if (length < 0.5 || length > 1.5) return null
        val x = x0 / length
        val y = y0 / length
        val z = z0 / length
        val w = w0 / length
        // Columns of the device-to-world matrix. Screen-up follows display rotation.
        val up = when (screenRotation) {
            0 -> doubleArrayOf(2 * (x * y - z * w), 1 - 2 * (x * x + z * z))
            1 -> doubleArrayOf(-(1 - 2 * (y * y + z * z)), -2 * (x * y + z * w))
            2 -> doubleArrayOf(-2 * (x * y - z * w), -(1 - 2 * (x * x + z * z)))
            3 -> doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y + z * w))
            else -> error("Display rotation must be 0..3")
        }
        val fx = up[0] - 2 * (x * z + y * w)
        val fy = up[1] - 2 * (y * z - x * w)
        // Screen down or nearly vertical forward projection: direction is ambiguous.
        if (1 - 2 * (x * x + y * y) < -config.headingMinProjection ||
            hypot(fx, fy) < config.headingMinProjection
        ) {
            previous = null
            return null
        }
        val raw = atan2(fx, fy)
        val old = previous
        val dt = old?.let { (timestampNs - it.timestampNs) / 1e9 }
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
