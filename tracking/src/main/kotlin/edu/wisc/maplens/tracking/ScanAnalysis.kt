package edu.wisc.maplens.tracking

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the back camera points, from a game-rotation-vector quaternion (ADR 0006).
 *
 * [yawRad] is in the same clockwise, arbitrary-zero frame as [HeadingReading.relativeRad]
 * (for an upright phone the two agree), so an offset found from a scan applies to both.
 * [pitchRad] is the camera's elevation (+ up); [rollRad] is how far the image's right edge
 * is raised (+ means the horizon slopes down to the right in the picture).
 */
data class CameraPose(val yawRad: Double, val pitchRad: Double, val rollRad: Double) {
    companion object {
        fun fromRotationVector(vector: FloatArray): CameraPose? {
            if (vector.size < 3 || vector.any { !it.isFinite() }) return null
            val x0 = vector[0].toDouble()
            val y0 = vector[1].toDouble()
            val z0 = vector[2].toDouble()
            val w0 = vector.getOrNull(3)?.toDouble()
                ?: sqrt((1 - x0 * x0 - y0 * y0 - z0 * z0).coerceAtLeast(0.0))
            val n = sqrt(x0 * x0 + y0 * y0 + z0 * z0 + w0 * w0)
            if (n < 0.5 || n > 1.5) return null
            val x = x0 / n
            val y = y0 / n
            val z = z0 / n
            val w = w0 / n
            // Camera looks along −R·ẑ; image right is R·x̂ (portrait, back camera).
            val fx = -2 * (x * z + y * w)
            val fy = -2 * (y * z - x * w)
            val fz = -(1 - 2 * (x * x + y * y))
            val rightZ = 2 * (x * z - y * w)
            if (hypot(fx, fy) < 1e-6) return null
            return CameraPose(
                atan2(fx, fy),
                asin(fz.coerceIn(-1.0, 1.0)),
                asin(rightZ.coerceIn(-1.0, 1.0)),
            )
        }
    }
}

/** One captured photo's geometry: upright image size and horizontal field of view. */
data class ScanFrame(val pose: CameraPose, val width: Int, val height: Int, val hfovRad: Double) {
    /** Focal length in pixels of this image. */
    val focalPx: Double get() = width / 2.0 / tan(hfovRad / 2)

    /** Relative-frame bearing of image column [x] (pixels from the left edge). */
    fun bearingOfColumn(
        x: Double
    ): Double = wrapHeading(pose.yawRad + atan((x - width / 2.0) / focalPx))

    /** Image row of the horizon at column [x]: lower in the picture when the camera tilts up. */
    fun horizonRow(x: Double): Double =
        height / 2.0 + focalPx * tan(pose.pitchRad) + (x - width / 2.0) * tan(pose.rollRad)
}

/** A strong horizontal vanishing point, as an angle from the image centre (+ is right). */
data class VanishingPoint(val angleRad: Double, val strength: Double, val inView: Boolean)

/**
 * Finds where a corridor's long lines meet the horizon (Kim and Manduchi style, ADR 0006).
 *
 * Every strong edge pixel votes for where its line crosses the horizon; converging floor,
 * wall and ceiling lines pile their votes on one angle. Vertical edges (door frames) and
 * edges parallel to the horizon say nothing about depth and are skipped. Works on a small
 * greyscale copy of the photo, so it is cheap and needs no OpenCV.
 */
class VanishingPointFinder(private val config: TrackingConfig = TrackingConfig()) {
    fun find(gray: FloatArray, frame: ScanFrame): VanishingPoint? {
        val w = frame.width
        val h = frame.height
        require(gray.size == w * h && w >= 3 && h >= 3)
        val f = frame.focalPx
        val slope = tan(frame.pose.rollRad)
        // Light blur first so pixel staircases and sensor noise don't set edge directions.
        val img = boxBlur(boxBlur(gray, w, h, 1), w, h, 1)
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        var maxMag = 0.0
        for (r in 1 until h - 1) {
            for (c in 1 until w - 1) {
                val i = r * w + c
                // Sobel.
                gx[i] = (img[i - w + 1] + 2 * img[i + 1] + img[i + w + 1]) -
                    (img[i - w - 1] + 2 * img[i - 1] + img[i + w - 1])
                gy[i] = (img[i + w - 1] + 2 * img[i + w] + img[i + w + 1]) -
                    (img[i - w - 1] + 2 * img[i - w] + img[i - w + 1])
                maxMag = maxOf(maxMag, hypot(gx[i].toDouble(), gy[i].toDouble()))
            }
        }
        if (maxMag <= 0) return null
        // Structure tensor: edge direction from a neighbourhood, plus how line-like it is.
        val radius = config.orientVpTensorRadius
        val jxx = boxBlur(FloatArray(w * h) { gx[it] * gx[it] }, w, h, radius)
        val jyy = boxBlur(FloatArray(w * h) { gy[it] * gy[it] }, w, h, radius)
        val jxy = boxBlur(FloatArray(w * h) { gx[it] * gy[it] }, w, h, radius)
        val bins = 2 * VP_RANGE_DEG + 1
        val votes = DoubleArray(bins)
        val minMag = maxMag * config.orientVpEdgeFraction
        val minFromVertical = sin(Math.toRadians(config.orientVpSkipVerticalDeg))
        val minFromHorizon = sin(Math.toRadians(config.orientVpSkipHorizontalDeg))
        for (r in 1 until h - 1) {
            for (c in 1 until w - 1) {
                val i = r * w + c
                val mag = hypot(gx[i].toDouble(), gy[i].toDouble())
                if (mag < minMag) continue
                val a = jxx[i].toDouble()
                val b = jyy[i].toDouble()
                val cxy = jxy[i].toDouble()
                val trace = a + b
                if (trace <= 0) continue
                // Corners and texture have no single direction; only lines vote.
                if (sqrt((a - b) * (a - b) + 4 * cxy * cxy) / trace < config.orientVpMinCoherence) {
                    continue
                }
                // The edge runs perpendicular to the dominant gradient direction.
                val phi = 0.5 * atan2(2 * cxy, a - b)
                val dx = -sin(phi)
                val dy = cos(phi)
                if (abs(dx) < minFromVertical) continue
                // Angle between the edge and the (possibly tilted) horizon.
                val cross = abs(dy - slope * dx) / hypot(1.0, slope)
                if (cross < minFromHorizon) continue
                val t = (frame.horizonRow(c.toDouble()) - r) / (dy - slope * dx)
                val angle = Math.toDegrees(atan((c + t * dx - w / 2.0) / f))
                if (abs(angle) > VP_RANGE_DEG) continue
                votes[(angle + VP_RANGE_DEG).roundToInt()] += mag
            }
        }
        val total = votes.sum()
        if (total <= 0) return null
        val window = config.orientVpPeakHalfWidthDeg
        val mass = DoubleArray(bins) { b ->
            (b - window..b + window).sumOf { votes.getOrElse(it) { 0.0 } }
        }
        val peak = mass.indices.maxBy { mass[it] }
        val strength = mass[peak] / total
        if (strength < config.orientVpMinStrength) return null
        // Vote-weighted mean inside the window for sub-degree precision.
        val lo = maxOf(0, peak - window)
        val hi = minOf(bins - 1, peak + window)
        val meanDeg = (lo..hi).sumOf { (it - VP_RANGE_DEG) * votes[it] } /
            (lo..hi).sumOf { votes[it] }
        val angle = Math.toRadians(meanDeg)
        val halfView = frame.hfovRad / 2 * config.orientVpInViewFraction
        return VanishingPoint(angle, strength, abs(angle) <= halfView)
    }

    private companion object {
        const val VP_RANGE_DEG = 80

        /** Mean over a (2r+1)² square, clamped at the borders; separable running sums. */
        fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val tmp = FloatArray(w * h)
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (k in -r..r) sum += src[y * w + (x + k).coerceIn(0, w - 1)]
                    tmp[y * w + x] = sum / (2 * r + 1)
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    for (k in -r..r) sum += tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    out[y * w + x] = sum / (2 * r + 1)
                }
            }
            return out
        }
    }
}

/** One OCR line or word and its horizontal centre in the upright image. */
data class TextHit(val text: String, val centerX: Double)

/**
 * Room-number matching for scans (A5 normalization): upper-case, keep letters and digits,
 * then read O as 0, I or L as 1 and S as 5 in the numeric part. Only labels that exist in
 * the graph are returned, so a random word can't become a door.
 */
fun matchRoomLabel(text: String, labels: Set<String>): String? {
    val upper = labels.map { it.uppercase() }.toSet()
    for (token in text.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.length >= 3 }) {
        if (token in upper) return token
        // Try keeping a trailing suite letter (3539A) and also reading it as a digit (344S).
        for (keepLast in listOf(true, false)) {
            val fixed = token.mapIndexed { index, ch ->
                if (keepLast && index == token.lastIndex && index >= 3) {
                    ch
                } else {
                    when (ch) {
                        'O', 'D' -> '0'
                        'I', 'L' -> '1'
                        'S' -> '5'
                        'B' -> '8'
                        else -> ch
                    }
                }
            }.joinToString("")
            if (fixed in upper) return fixed
        }
    }
    return null
}

/**
 * Turns one analysed photo into solver observations. A photo that reads several room numbers
 * at once is a posted floor map, not a corridor: its numbers are printed on the map, so they
 * become one [OrientObservation.MapUp] instead of doors.
 */
fun scanObservations(
    frame: ScanFrame,
    vanishing: VanishingPoint?,
    texts: List<TextHit>,
    labels: Set<String>,
    config: TrackingConfig = TrackingConfig(),
): List<OrientObservation> = buildList {
    vanishing?.let {
        val bearing = wrapHeading(frame.pose.yawRad + it.angleRad)
        add(OrientObservation.CorridorAxis(bearing))
        if (it.inView) add(OrientObservation.Opening(bearing))
    }
    val doors = texts.mapNotNull { hit ->
        matchRoomLabel(
            hit.text,
            labels
        )?.let { it to hit.centerX }
    }
        .distinctBy { it.first }
    if (doors.size >= config.orientPosterMinLabels) {
        add(OrientObservation.MapUp(frame.pose.yawRad))
    } else {
        doors.forEach { (label, x) -> add(OrientObservation.Door(label, frame.bearingOfColumn(x))) }
    }
}
