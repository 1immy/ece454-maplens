package edu.wisc.maplens.mapparse.classic

import edu.wisc.maplens.mapparse.PosterRetakeException
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Owns temporary native allocations for one stage. */
internal class Mats : AutoCloseable {
    private val allocated = mutableListOf<Mat>()

    fun <T : Mat> own(mat: T): T = mat.also(allocated::add)

    fun mat(): Mat = own(Mat())

    fun kernel(width: Int, height: Int = width, shape: Int = Imgproc.MORPH_RECT): Mat =
        own(Imgproc.getStructuringElement(shape, Size(width.toDouble(), height.toDouble())))

    override fun close() = allocated.asReversed().forEach(Mat::release)
}

internal fun checkCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Poster scan cancelled")
}

internal class PosterFrame {
    fun corners(bgr: Mat): List<Point> = Mats().use { mats ->
        val scale = 1500.0 / maxOf(bgr.cols(), bgr.rows())
        val small = mats.mat()
        Imgproc.resize(
            bgr,
            small,
            Size(
                (bgr.cols() * scale).roundToInt().toDouble(),
                (bgr.rows() * scale).roundToInt().toDouble()
            ),
            0.0,
            0.0,
            Imgproc.INTER_AREA,
        )
        val blue = mats.mat()
        Imgproc.cvtColor(small, blue, Imgproc.COLOR_BGR2Lab)
        Core.extractChannel(blue, blue, 2)
        Imgproc.GaussianBlur(blue, blue, Size(5.0, 5.0), 0.0)
        val width = blue.cols()
        val height = blue.rows()
        val pixels = ByteArray(width * height)
        blue.get(0, 0, pixels)
        val margin = maxOf(2, (0.06 * minOf(width, height)).roundToInt())
        val histogram = IntArray(256)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = pixels[y * width + x].toInt() and 255
                if (x < margin || x >= width - margin) histogram[value]++
                if (y < margin || y >= height - margin) histogram[value]++
            }
        }
        val middle = (width + height) * margin
        var count = 0
        val median = histogram.indexOfFirst {
            count += it
            count > middle
        }
        val threshold = (median - 7).coerceIn(121, 128)
        // A near-neutral wall can put the first cutoff just below a faded blue frame.
        val thresholds = listOf(threshold, minOf(threshold + 2, 128), minOf(threshold + 4, 128))
        var reason = "Blue map frame not found. Include the entire blue border."
        for (cutoff in thresholds.distinct()) {
            checkCancellation()
            try {
                return@use findFrame(blue, cutoff).map { Point(it.x / scale, it.y / scale) }
            } catch (failure: FrameRejected) {
                if (failure.clipped) throw PosterRetakeException(checkNotNull(failure.message))
                reason = checkNotNull(failure.message)
            }
        }
        throw PosterRetakeException(reason)
    }

    private class FrameRejected(message: String, val clipped: Boolean = false) : Exception(message)

    private fun findFrame(chroma: Mat, cutoff: Int): List<Point> = Mats().use { mats ->
        val width = chroma.cols()
        val height = chroma.rows()
        val blue = mats.mat()
        Imgproc.threshold(chroma, blue, cutoff - 1.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Imgproc.morphologyEx(blue, blue, Imgproc.MORPH_OPEN, mats.kernel(3))
        val flood = mats.own(blue.clone())
        Imgproc.floodFill(flood, mats.mat(), Point(0.0, 0.0), Scalar(255.0))
        Core.bitwise_not(flood, flood)
        Core.bitwise_or(blue, flood, blue)
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            blue,
            contours,
            mats.mat(),
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_NONE
        )
        contours.forEach(mats::own)
        val hullIndices = mats.own(MatOfInt())
        val candidate = contours.map { contour ->
            Imgproc.convexHull(contour, hullIndices)
            val points = contour.toArray()
            val hull = mats.own(
                MatOfPoint(*hullIndices.toArray().map { points[it] }.toTypedArray())
            )
            Triple(contour, hull.toArray(), Imgproc.contourArea(hull))
        }.maxByOrNull { it.third }
        if (candidate == null || candidate.third < 0.03 * width * height) {
            throw FrameRejected("Blue map frame not found. Include the entire blue border.")
        }
        val (contour, hull, _) = candidate
        val border = contour.toArray()
        val clipped = border.count {
            it.x <= 2 || it.x >= width - 3 || it.y <= 2 || it.y >= height - 3
        }
        if (clipped > 0.02 * border.size) {
            throw FrameRejected(
                "Map is cut off. Step back and include all four corners.",
                clipped = true,
            )
        }
        val quad = refine(hull, rough(hull, mats), mats)
        val polygon = mats.own(MatOfPoint2f(*quad.toTypedArray()))
        val area = Imgproc.contourArea(polygon)
        val drawn = mats.mat()
        blue.copyTo(drawn)
        drawn.setTo(Scalar(0.0))
        Imgproc.drawContours(drawn, listOf(contour), 0, Scalar(255.0), Imgproc.FILLED)
        Core.bitwise_and(drawn, blue, drawn)
        if (area <= 0 || Core.countNonZero(drawn) / area < 0.85) {
            throw FrameRejected("Frame is obscured. Hold steady and retake without glare.")
        }
        order(quad)
    }

    /** Mean top and bottom edge length of [quad], in its own pixels. */
    fun width(quad: List<Point>): Double {
        val (tl, tr, br, bl) = quad
        return (distance(tl, tr) + distance(bl, br)) / 2
    }

    fun warp(bgr: Mat, quad: List<Point>, output: Mat) = Mats().use { mats ->
        val (tl, tr, br, bl) = quad
        val horizontal = distance(tl, tr) + distance(bl, br)
        val vertical = distance(tl, bl) + distance(tr, br)
        val width = 2000
        val height = (width * vertical / horizontal).roundToInt().coerceAtLeast(1)
        if (height !in 2..3200) {
            throw PosterRetakeException("Frame has an extreme aspect ratio. Retake straight on.")
        }
        val source = mats.own(MatOfPoint2f(*quad.toTypedArray()))
        val right = (width - 1).toDouble()
        val bottom = (height - 1).toDouble()
        val target = mats.own(
            MatOfPoint2f(
                Point(),
                Point(right, 0.0),
                Point(right, bottom),
                Point(0.0, bottom)
            )
        )
        val transform = mats.own(Imgproc.getPerspectiveTransform(source, target))
        Imgproc.warpPerspective(
            bgr,
            output,
            transform,
            Size(width.toDouble(), height.toDouble()),
            Imgproc.INTER_CUBIC
        )
    }

    private fun rough(hull: Array<Point>, mats: Mats): List<Point> {
        val points = mats.own(MatOfPoint2f(*hull))
        val approx = mats.own(MatOfPoint2f())
        val length = Imgproc.arcLength(points, true)
        for (i in 0..18) {
            Imgproc.approxPolyDP(points, approx, (0.01 + i * 0.005) * length, true)
            if (approx.total() == 4L) return order(approx.toList())
        }
        val box = Array(4) { Point() }
        Imgproc.minAreaRect(points).points(box)
        return order(box.toList())
    }

    private fun refine(hull: Array<Point>, quad: List<Point>, mats: Mats): List<Point> {
        val lines = quad.indices.map { i ->
            val a = quad[i]
            val b = quad[(i + 1) % quad.size]
            val length = distance(a, b)
            if (length < 1) return quad
            val ux = (b.x - a.x) / length
            val uy = (b.y - a.y) / length
            val side = hull.filter {
                val dx = it.x - a.x
                val dy = it.y - a.y
                val along = dx * ux + dy * uy
                along > 0.1 * length && along < 0.9 * length &&
                    abs(dy * ux - dx * uy) < maxOf(3.0, 0.015 * length)
            }
            if (side.size < 10) {
                doubleArrayOf(ux, uy, a.x, a.y)
            } else {
                fitLine(side, Imgproc.DIST_HUBER, mats)
            }
        }
        return lines.indices.map { i ->
            val (ax, ay, x, y) = lines[(i + 3) % 4]
            val (bx, by, u, v) = lines[i]
            val determinant = ax * by - ay * bx
            if (abs(determinant) < 1e-9) return quad
            val t = ((u - x) * by - (v - y) * bx) / determinant
            Point(x + t * ax, y + t * ay)
        }
    }

    private fun order(points: List<Point>): List<Point> {
        val ordered = listOf(
            points.minBy { it.x + it.y },
            points.minBy { it.y - it.x },
            points.maxBy { it.x + it.y },
            points.maxBy { it.y - it.x },
        )
        if (ordered.distinct().size != 4) {
            throw PosterRetakeException("Four distinct corners are needed. Retake straight on.")
        }
        return ordered
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
}

internal fun fitLine(points: List<Point>, distance: Int, mats: Mats): DoubleArray {
    val input = mats.own(MatOfPoint2f(*points.toTypedArray()))
    val line = mats.mat()
    Imgproc.fitLine(input, line, distance, 0.0, 0.01, 0.01)
    val values = FloatArray(4)
    line.get(0, 0, values)
    return DoubleArray(4) { values[it].toDouble() }
}
