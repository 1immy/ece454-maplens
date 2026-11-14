package edu.wisc.maplens.mapparse

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.sqrt
import org.opencv.core.Core
import org.opencv.core.Point

/** Why a preview frame shows no usable map frame. */
enum class SightingProblem { NOT_FOUND, CUT_OFF, OBSCURED }

/**
 * One look at the poster in a live preview frame, in the upright frame's pixels ([width] ×
 * [height]). [corners] is TL, TR, BR, BL as x, y pairs, or null with a [problem].
 *
 * [yawDegrees] and [pitchDegrees] say how far the camera looks across the wall rather than
 * straight at it: negative yaw means the phone is to the left of the map, negative pitch that
 * it is above it. [fill] is the share of the frame's width or height the map spans, whichever
 * is larger.
 */
class PosterSighting(
    val width: Int,
    val height: Int,
    val corners: DoubleArray?,
    val problem: SightingProblem?,
    val yawDegrees: Double = 0.0,
    val pitchDegrees: Double = 0.0,
    val fill: Double = 0.0,
)

/**
 * Finds the blue map frame in small preview frames, so the capture screen can say how to hold
 * the phone before the photo. Same detector as the parse, on a smaller copy.
 */
object PosterAim {
    /**
     * Long side the detector works at. Nov 14, Pixel 7a: at 960 px two of the 16 fixture photos
     * got a corner wrong, at 1280 none, about 33 ms a look. Settable for the probe.
     */
    internal var workSide = 1280.0

    /**
     * [frame] as the camera delivered it, [rotationDegrees] clockwise to upright, and the
     * lens's focal length in [frame] pixels.
     */
    fun look(frame: Bitmap, rotationDegrees: Int, focalPx: Double): PosterSighting =
        Mats().use { mats ->
            requireOpenCv()
            val bgr = bitmapBgr(frame, mats)
            when (rotationDegrees) {
                90 -> Core.rotate(bgr, bgr, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(bgr, bgr, Core.ROTATE_180)
                270 -> Core.rotate(bgr, bgr, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            val width = bgr.cols()
            val height = bgr.rows()
            val corners = try {
                PosterFrame().corners(bgr, workSide)
            } catch (failure: PosterRetakeException) {
                val message = failure.message.orEmpty()
                val problem = when {
                    "cut off" in message -> SightingProblem.CUT_OFF
                    "obscured" in message -> SightingProblem.OBSCURED
                    else -> SightingProblem.NOT_FOUND
                }
                return@use PosterSighting(width, height, null, problem)
            }
            val flat = corners.flatMap { listOf(it.x, it.y) }.toDoubleArray()
            val (yaw, pitch) = wallFacing(flat, width / 2.0, height / 2.0, focalPx)
            PosterSighting(width, height, flat, null, yaw, pitch, fill(corners, width, height))
        }

    private fun fill(corners: List<Point>, width: Int, height: Int): Double = maxOf(
        (corners.maxOf { it.x } - corners.minOf { it.x }) / width,
        (corners.maxOf { it.y } - corners.minOf { it.y }) / height,
    )
}

/**
 * Yaw and pitch, in degrees, of a rectangle on the wall seen as the quad [corners] (TL, TR, BR,
 * BL as x, y pairs) by a pinhole camera with principal point ([cx], [cy]) and focal length
 * [focalPx]. Zero for both means the phone is parallel to the wall. The wall's direction is
 * the cross product of the two vanishing directions of the quad's edge pairs; that works for
 * parallel edges too (a vanishing point at infinity).
 */
fun wallFacing(
    corners: DoubleArray,
    cx: Double,
    cy: Double,
    focalPx: Double
): Pair<Double, Double> {
    require(corners.size == 8)
    val rays = List(4) { doubleArrayOf(corners[2 * it] - cx, corners[2 * it + 1] - cy, focalPx) }
    val (tl, tr, br, bl) = rays
    val across = cross(cross(tl, tr), cross(bl, br))
    val down = cross(cross(tl, bl), cross(tr, br))
    val normal = cross(across, down)
    val length = sqrt(normal.sumOf { it * it })
    if (length == 0.0 || !length.isFinite()) return 0.0 to 0.0
    val sign = if (normal[2] < 0) -1.0 else 1.0
    val (nx, ny, nz) = normal.map { it * sign / length }
    val degrees = 180 / Math.PI
    return atan2(nx, nz) * degrees to atan2(ny, nz) * degrees
}

private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
)
