package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.PixelPoint
import kotlin.math.ceil
import kotlin.math.floor
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfInt4
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

internal fun detectStars(hsv: Mat): List<PixelPoint> {
    val strict = starCandidates(hsv, Scalar(14.0, 90.0, 70.0))
    val warm = starCandidates(hsv, Scalar(10.0, 70.0, 70.0))
    return strict + warm.filter { candidate -> strict.none { it.distanceTo(candidate) < 20.0 } }
}

private fun starCandidates(hsv: Mat, goldLow: Scalar): List<PixelPoint> = Mats().use { mats ->
    val gold = mats.mat()
    val blue = mats.mat()
    Core.inRange(hsv, goldLow, Scalar(38.0, 255.0, 255.0), gold)
    Core.inRange(hsv, Scalar(95.0, 80.0, 25.0), Scalar(135.0, 255.0, 255.0), blue)
    Imgproc.morphologyEx(gold, gold, Imgproc.MORPH_CLOSE, mats.kernel(3))
    val contours = mutableListOf<MatOfPoint>()
    Imgproc.findContours(
        gold,
        contours,
        mats.mat(),
        Imgproc.RETR_EXTERNAL,
        Imgproc.CHAIN_APPROX_SIMPLE
    )
    contours.forEach(mats::own)
    val indices = mats.own(MatOfInt())
    val hull = mats.own(MatOfPoint())
    val defects = mats.own(MatOfInt4())
    val width = blue.cols()
    val height = blue.rows()
    val bluePixels = ByteArray(width * height)
    blue.get(0, 0, bluePixels)
    contours.mapNotNull { contour ->
        val area = Imgproc.contourArea(contour)
        if (area !in 250.0..2000.0) return@mapNotNull null
        val box = Imgproc.boundingRect(contour)
        if (box.width.toDouble() / box.height !in 0.65..1.5) return@mapNotNull null
        Imgproc.convexHull(contour, indices)
        val points = contour.toArray()
        hull.fromList(indices.toList().map { points[it] })
        if (area / Imgproc.contourArea(hull) !in 0.35..0.80) return@mapNotNull null
        try {
            Imgproc.convexityDefects(contour, indices, defects)
        } catch (_: CvException) {
            // OpenCV refuses a self-intersecting contour. A poster must not fail over one blob.
            return@mapNotNull null
        }
        val side = maxOf(box.width, box.height)
        val depths = defects.toArray()
        val deep = (3 until depths.size step 4).count { depths[it] / 256.0 > 0.07 * side }
        if (deep < 4) return@mapNotNull null
        val x = box.x + (box.width - 1) / 2.0
        val y = box.y + (box.height - 1) / 2.0
        val radius = 0.8 * side
        var total = 0
        var blueCount = 0
        val top = floor(y - radius).toInt().coerceAtLeast(0)
        val bottom = ceil(y + radius).toInt().coerceAtMost(height - 1)
        val left = floor(x - radius).toInt().coerceAtLeast(0)
        val right = ceil(x + radius).toInt().coerceAtMost(width - 1)
        for (row in top..bottom) {
            for (col in left..right) {
                val dx = col - x
                val dy = row - y
                if (dx * dx + dy * dy > radius * radius) continue
                total++
                if (bluePixels[row * width + col] != 0.toByte()) blueCount++
            }
        }
        if (blueCount >= 0.15 * total) PixelPoint(x, y) else null
    }
}
