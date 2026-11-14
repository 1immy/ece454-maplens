package edu.wisc.maplens.mapparse

import android.graphics.Rect
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** How a room number crossed by a wall line is found and read again. */
internal data class CutNumberParams(
    /** A short read at least this share of a full room number's width lost a character. */
    val minWidthShare: Double = 0.85,
    /** Rereads below this confidence are not believed. */
    val minConfidence: Float = 0.8f,
    /** Crop padding around the read, in text heights: wide, so a wall line runs long in it. */
    val padX: Double = 0.6,
    val padY: Double = 0.3,
    /** Dark runs this long, in text heights, are wall lines; a glyph's strokes are shorter. */
    val verticalLine: Double = 1.3,
    val horizontalLine: Double = 0.9,
)

/**
 * Room numbers a wall line crosses lose the crossed digit: EH3's "3540" has a wall corner
 * through its 0 and reads "354". Such a read is a digit short of the poster's room numbers
 * but as wide as a full one. Each is read again from a crop with the long straight lines
 * erased, and replaced when the reread is a room number on [floor] that keeps every digit
 * already read. Other reads pass through unchanged.
 */
internal fun repairCutNumbers(
    bgr: Mat,
    readings: List<PosterReading>,
    floor: Char?,
    params: CutNumberParams = CutNumberParams(),
): List<PosterReading> {
    val suspects = cutNumberSuspects(readings, floor, params)
    if (suspects.isEmpty()) return readings
    // One copy of the map with the lines erased around every suspect, read in one batch.
    val clean = bgr.clone()
    try {
        // A crossing line makes the read's own box taller: size lines by a clean number's.
        val height = readings.filter { !it.possible && normalizeRoom(it.text) != null }
            .map { it.box.height() }.sorted().let { it[it.size / 2] }
        val areas = suspects.map { i -> eraseLines(clean, readings[i].box, height, params) }
        val rereads = recheckEach(clean, areas)
        val fixed = readings.toMutableList()
        suspects.forEachIndexed { k, i ->
            val short = readings[i]
            val better = rereads[k].filter { it.confidence >= params.minConfidence }
                .mapNotNull { r -> normalizeRoom(r.text)?.let { r.copy(text = it) } }
                .firstOrNull { keepsDigits(digitsOf(short.text), it.text, floor) }
            if (better != null) {
                fixed[i] = short.copy(
                    text = better.text,
                    confidence = minOf(short.confidence, better.confidence),
                )
            }
        }
        return fixed
    } finally {
        clean.release()
    }
}

/** Indexes of reads a digit short of the poster's room numbers yet as wide as one. */
internal fun cutNumberSuspects(
    readings: List<PosterReading>,
    floor: Char?,
    params: CutNumberParams = CutNumberParams(),
): List<Int> {
    val full = readings.filter { !it.possible && normalizeRoom(it.text) != null }
    if (full.isEmpty()) return emptyList()
    val digits = full.map { digitsOf(normalizeRoom(it.text)!!).length }
        .groupingBy { it }.eachCount().maxBy { it.value }.key
    val widths = full.filter { normalizeRoom(it.text)!!.length == digits }
        .map { it.box.width() }.sorted()
    if (widths.isEmpty()) return emptyList()
    val typical = widths[widths.size / 2]
    return readings.indices.filter { i ->
        val r = readings[i]
        val text = digitsOf(r.text)
        val onlyDigits = text.length == r.text.count(Char::isLetterOrDigit)
        !r.possible && text.length == digits - 1 && onlyDigits &&
            (floor == null || text.first() == floor) &&
            r.box.width() >= params.minWidthShare * typical &&
            // A full number read over the same print already names it.
            full.none { f -> Rect.intersects(f.box, r.box) && digitsOf(f.text).contains(text) }
    }
}

/** True when [reread] is a room number on [floor] holding every digit of [short], in order. */
internal fun keepsDigits(short: String, reread: String, floor: Char?): Boolean {
    val digits = digitsOf(reread)
    if (digits.length != short.length + 1) return false
    if (floor != null && digits.first() != floor) return false
    var i = 0
    for (c in digits) if (i < short.length && c == short[i]) i++
    return i == short.length
}

private fun digitsOf(text: String) = text.filter(Char::isDigit)

/**
 * Paints long straight lines (walls) around [box] in paper colour, in place in [image], and
 * returns the area to read again.
 */
private fun eraseLines(
    image: Mat,
    box: Rect,
    h: Int,
    params: CutNumberParams,
): Rect = Mats().use { mats ->
    val area = Rect(
        (box.left - (params.padX * h).toInt()).coerceAtLeast(0),
        (box.top - (params.padY * h).toInt()).coerceAtLeast(0),
        (box.right + (params.padX * h).toInt()).coerceAtMost(image.cols()),
        (box.bottom + (params.padY * h).toInt()).coerceAtMost(image.rows()),
    )
    val crop = mats.own(image.submat(area.top, area.bottom, area.left, area.right))
    val dark = darkInk(crop, mats)
    val vertical = mats.mat()
    Imgproc.morphologyEx(
        dark,
        vertical,
        Imgproc.MORPH_OPEN,
        mats.kernel(1, (params.verticalLine * h).toInt().coerceAtLeast(3)),
    )
    val horizontal = mats.mat()
    Imgproc.morphologyEx(
        dark,
        horizontal,
        Imgproc.MORPH_OPEN,
        mats.kernel((params.horizontalLine * h).toInt().coerceAtLeast(3), 1),
    )
    val lines = mats.mat()
    Core.bitwise_or(vertical, horizontal, lines)
    Imgproc.dilate(lines, lines, mats.kernel(3))
    val paperMask = mats.mat()
    Core.bitwise_not(dark, paperMask)
    val paper: Scalar = Core.mean(crop, paperMask)
    crop.setTo(paper, lines)
    area
}
