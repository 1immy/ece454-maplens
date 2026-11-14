package edu.wisc.maplens.mapparse

import android.graphics.Rect
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Word-sized proposals from room_labels.py, independent of recognized text. */
internal fun roomWordRegions(bgr: Mat): List<Rect> = Mats().use { mats ->
    val dark = darkInk(bgr, mats)
    val labels = mats.mat()
    val stats = mats.mat()
    val centroids = mats.mat()
    val count = Imgproc.connectedComponentsWithStats(dark, labels, stats, centroids)
    val glyphs = BooleanArray(count)
    val row = IntArray(5)
    for (i in 1 until count) {
        stats.get(i, 0, row)
        val (_, _, width, height, area) = row
        glyphs[i] = height in 10..45 && width <= 160 && area in 12..4000
    }
    val pixels = IntArray(dark.cols() * dark.rows())
    labels.get(0, 0, pixels)
    val foreground = ByteArray(pixels.size) { if (glyphs[pixels[it]]) -1 else 0 }
    dark.put(0, 0, foreground)
    val joined = mats.mat()
    Imgproc.dilate(dark, joined, mats.kernel(11, 3))
    val words = Imgproc.connectedComponentsWithStats(joined, labels, stats, centroids)
    buildList {
        for (i in 1 until words) {
            stats.get(i, 0, row)
            val (x, y, width, height) = row
            if (width !in 30..180 || height !in 12..50 || width < 1.5 * height) continue
            add(
                Rect(
                    (x - 5).coerceAtLeast(0),
                    (y - 5).coerceAtLeast(0),
                    (x + width + 5).coerceAtMost(dark.cols()),
                    (y + height + 5).coerceAtMost(dark.rows()),
                ),
            )
        }
    }
}

internal fun darkInk(bgr: Mat, mats: Mats): Mat {
    val dark = mats.mat()
    Imgproc.cvtColor(bgr, dark, Imgproc.COLOR_BGR2GRAY)
    Imgproc.adaptiveThreshold(
        dark,
        dark,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        31,
        12.0,
    )
    return dark
}

/**
 * Real ink on the poster for the door finder: a doorway with ink across it is a symbol (a
 * column ring, text, an arrow), not a door. Hatching dots that fill corridors are dropped.
 */
internal fun doorInk(bgr: Mat): ByteArray = Mats().use { mats ->
    val dark = darkInk(bgr, mats)
    val labels = mats.mat()
    val stats = mats.mat()
    val count = Imgproc.connectedComponentsWithStats(dark, labels, stats, mats.mat(), 8)
    val small = BooleanArray(count) { i ->
        i > 0 && stats.get(i, Imgproc.CC_STAT_AREA)[0] < SPECK_PX
    }
    val ids = IntArray(dark.cols() * dark.rows()).also { labels.get(0, 0, it) }
    ByteArray(ids.size) { if (ids[it] != 0 && !small[ids[it]]) -1 else 0 }
}

/** Ink blobs smaller than this are hatching dots or noise, not marks across a doorway. */
private const val SPECK_PX = 15
