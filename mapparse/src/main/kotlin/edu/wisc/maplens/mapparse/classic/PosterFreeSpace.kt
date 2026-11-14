package edu.wisc.maplens.mapparse.classic

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

private const val WALL_LONG = 120 // dark shapes this long are walls whatever their shape...
private const val WALL_SHORT = 30 // ...shorter ones must be this long and 3 times as long as wide
private const val PAPER_BLUE = 6 // Lab b* units bluer than paper means frame or backdrop
private const val PAINT_MARGIN = 7 // px around paint that is never wall
private const val OFF_PAPER_SIZE = 21 // frame and backdrop are at least this wide; text is not
private const val FIRST_SEAL = 15 // door gaps closed before the corridor width is known
private const val WIDTH_RUN = 200 // free runs this long are measured for corridor width
private const val MIN_WIDTH = 12
private const val MAX_WIDTH = 400
private const val SEAL = 0.75 // door gaps up to this share of a corridor width are closed
private const val WIDE = 1.6 // free space wider than this many corridor widths is a room
private const val STRIP_LENGTH = 3.5 // a corridor runs straight for at least this many widths
private const val STRIP_RUN = 1.5 // an unbroken stretch of corridor is at least this many widths
private const val STRIP_GAP = 2.5 // a corridor keeps going across openings up to this many widths
private const val STRIP_JOIN = 0.8 // strips closer than this many widths are one corridor
internal const val KEEP_FRACTION = 0.05 // blobs under this share of the biggest are noise

private const val ON: Byte = -1

/** Where a person can stand, in pixels of the straightened poster. */
internal class FreeSpace(
    val rows: Int,
    val cols: Int,
    val blocked: ByteArray,
    val unsealed: ByteArray,
    val width: Int,
    val horizontal: IntArray,
    val vertical: IntArray,
) {
    fun isBlocked(x: Int, y: Int) = blocked[y * cols + x] != 0.toByte()

    fun inside(x: Int, y: Int) = x in 0 until cols && y in 0 until rows
}

/**
 * Dark, colourless, long shapes. Text and hatching dots are compact, so they stay out. Dark rust
 * paint can pass as a wall in a photo, and would split its corridor in two, so on a painted poster
 * the [paint] never counts.
 */
private fun longWalls(hsv: Mat, paint: Mat?, mats: Mats): Mat {
    val s = mats.mat()
    val v = mats.mat()
    Core.extractChannel(hsv, s, 1)
    Core.extractChannel(hsv, v, 2)
    val local = mats.mat()
    Imgproc.medianBlur(v, local, 31)
    val size = hsv.rows() * hsv.cols()
    val sat = ByteArray(size).also { s.get(0, 0, it) }
    val value = ByteArray(size).also { v.get(0, 0, it) }
    val around = ByteArray(size).also { local.get(0, 0, it) }
    val dark = ByteArray(size)
    for (i in 0 until size) {
        val sv = sat[i].toInt() and 255
        val vv = value[i].toInt() and 255
        val contrast = (around[i].toInt() and 255) - vv
        if (vv < 90 || (vv < 130 && sv < 90) || (contrast > 70 && sv < 50)) dark[i] = ON
    }
    if (paint != null) {
        val near = mats.mat()
        Imgproc.dilate(paint, near, mats.kernel(PAINT_MARGIN))
        val painted = ByteArray(size).also { near.get(0, 0, it) }
        for (i in 0 until size) if (painted[i] != 0.toByte()) dark[i] = 0
    }
    val darkMat = mats.own(Mat(hsv.rows(), hsv.cols(), CvType.CV_8U))
    darkMat.put(0, 0, dark)
    val labels = mats.mat()
    val stats = mats.mat()
    val count = Imgproc.connectedComponentsWithStats(darkMat, labels, stats, mats.mat())
    val keep = BooleanArray(count) {
        val w = stats.get(it, Imgproc.CC_STAT_WIDTH)[0].toInt()
        val h = stats.get(it, Imgproc.CC_STAT_HEIGHT)[0].toInt()
        val longer = maxOf(w, h)
        it != 0 && (longer >= WALL_LONG || (longer >= WALL_SHORT && longer >= 3 * minOf(w, h)))
    }
    val ids = IntArray(size).also { labels.get(0, 0, it) }
    for (i in 0 until size) dark[i] = if (keep[ids[i]]) ON else 0
    darkMat.put(0, 0, dark)
    return darkMat
}

/** The blue frame or backdrop: bluer than the poster's own paper, whatever the light. */
private fun offPaper(bgr: Mat, hsv: Mat, mats: Mats): Mat {
    val lab = mats.mat()
    Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
    val b = mats.mat()
    val s = mats.mat()
    val v = mats.mat()
    Core.extractChannel(lab, b, 2)
    Core.extractChannel(hsv, s, 1)
    Core.extractChannel(hsv, v, 2)
    val size = hsv.rows() * hsv.cols()
    val blue = ByteArray(size).also { b.get(0, 0, it) }
    val sat = ByteArray(size).also { s.get(0, 0, it) }
    val value = ByteArray(size).also { v.get(0, 0, it) }
    val counts = IntArray(256)
    for (i in 0 until size) {
        if ((sat[i].toInt() and 255) < 25 && (value[i].toInt() and 255) > 150) {
            counts[blue[i].toInt() and 255]++
        }
    }
    val mask = ByteArray(size)
    if (counts.any { it > 0 }) {
        val mode = counts.indices.maxBy { counts[it] }
        for (i in 0 until size) {
            if ((blue[i].toInt() and 255) < mode - PAPER_BLUE) mask[i] = ON
        }
    }
    val out = mats.own(Mat(hsv.rows(), hsv.cols(), CvType.CV_8U))
    out.put(0, 0, mask)
    // Coloured text such as "You Are Here" is bluer too, but it lies across corridors.
    Imgproc.morphologyEx(out, out, Imgproc.MORPH_OPEN, mats.kernel(OFF_PAPER_SIZE))
    return out
}

private fun seal(walls: Mat, size: Int, out: Mat, mats: Mats) {
    val across = mats.mat()
    Imgproc.morphologyEx(walls, out, Imgproc.MORPH_CLOSE, mats.kernel(size, 1))
    Imgproc.morphologyEx(walls, across, Imgproc.MORPH_CLOSE, mats.kernel(1, size))
    Core.bitwise_or(out, across, out)
    Core.bitwise_or(out, walls, out)
}

/** Each free pixel's run length along its row, or along its column when [down]. */
private fun runLengths(blocked: ByteArray, rows: Int, cols: Int, down: Boolean): IntArray {
    val out = IntArray(rows * cols)
    val lines = if (down) cols else rows
    val length = if (down) rows else cols
    val step = if (down) cols else 1 // index step along a line
    val next = if (down) 1 else cols // index step from one line's start to the next
    for (line in 0 until lines) {
        val first = line * next
        var t = 0
        while (t < length) {
            if (blocked[first + t * step] != 0.toByte()) {
                t++
                continue
            }
            val start = t
            while (t < length && blocked[first + t * step] == 0.toByte()) t++
            for (i in start until t) out[first + i * step] = t - start
        }
    }
    return out
}

/** Most common width of long straight free space, measured along the paint if there is some. */
private fun corridorWidth(h: IntArray, v: IntArray, blocked: ByteArray, routes: ByteArray?): Int {
    val counts = DoubleArray(MAX_WIDTH + 1)
    for (i in h.indices) {
        if (blocked[i] != 0.toByte() || maxOf(h[i], v[i]) < WIDTH_RUN) continue
        if (routes != null && routes[i] == 0.toByte()) continue
        counts[minOf(h[i], v[i]).coerceAtMost(MAX_WIDTH)]++
    }
    for (i in 0 until MIN_WIDTH) counts[i] = 0.0
    counts[MAX_WIDTH] = 0.0
    // A strip w wide has w pixels per unit of length, so divide to count length.
    for (i in 1..MAX_WIDTH) counts[i] /= i.toDouble()
    var best = MIN_WIDTH
    var bestScore = -1.0
    for (i in counts.indices) {
        var sum = 0.0
        for (j in maxOf(0, i - 2)..minOf(MAX_WIDTH, i + 2)) sum += counts[j]
        if (sum > bestScore) {
            bestScore = sum
            best = i
        }
    }
    return if (bestScore > 0) best else MIN_WIDTH
}

/**
 * Measures free space. When the poster has paint, [routes] is the widened paint mask and [paint]
 * the paint itself.
 */
internal fun freeSpace(bgr: Mat, routes: Mat?, paint: Mat?, mats: Mats): FreeSpace {
    val hsv = mats.mat()
    Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
    val walls = longWalls(hsv, paint, mats)
    val outside = offPaper(bgr, hsv, mats)
    val rows = bgr.rows()
    val cols = bgr.cols()
    val widened = routes?.let { ByteArray(rows * cols).also { bytes -> it.get(0, 0, bytes) } }

    fun measure(size: Int): Triple<ByteArray, IntArray, IntArray> {
        val sealed = mats.mat()
        seal(walls, size, sealed, mats)
        Core.bitwise_or(sealed, outside, sealed)
        val blocked = ByteArray(rows * cols).also { sealed.get(0, 0, it) }
        return Triple(
            blocked,
            runLengths(blocked, rows, cols, down = false),
            runLengths(blocked, rows, cols, down = true),
        )
    }

    val (firstBlocked, firstH, firstV) = measure(FIRST_SEAL)
    val first = corridorWidth(firstH, firstV, firstBlocked, widened)
    val (blocked, h, v) = measure(maxOf(FIRST_SEAL, (first * SEAL).toInt()))
    val width = maxOf(MIN_WIDTH, corridorWidth(h, v, blocked, widened))
    val open = mats.mat()
    Core.bitwise_or(walls, outside, open)
    val unsealed = ByteArray(rows * cols).also { open.get(0, 0, it) }
    return FreeSpace(rows, cols, blocked, unsealed, width, h, v)
}

/**
 * Corridors without paint: free space narrow one way and long the other. A room is wide both ways,
 * or too short. shortcut: axis-aligned corridors only, a diagonal wing needs the editor.
 */
internal fun stripMask(space: FreeSpace, mats: Mats): Mat {
    val width = space.width
    val wide = (width * WIDE).toInt()
    val length = (width * STRIP_LENGTH).toInt()
    val run = (width * STRIP_RUN).toInt()
    val gap = (width * STRIP_GAP).toInt() or 1
    val h = space.horizontal
    val v = space.vertical

    // A door gap lets free space leak into a room, so judge width by the narrowest point nearby.
    fun narrowest(runs: IntArray, kernel: Mat): FloatArray {
        val mat = mats.own(Mat(space.rows, space.cols, CvType.CV_32F))
        mat.put(0, 0, FloatArray(runs.size) { runs[it].toFloat() })
        Imgproc.erode(mat, mat, kernel)
        return FloatArray(runs.size).also { mat.get(0, 0, it) }
    }
    val tall = narrowest(v, mats.kernel(gap, 1))
    val broad = narrowest(h, mats.kernel(1, gap))

    fun strip(across: Boolean): Mat {
        val bytes = ByteArray(h.size)
        for (i in h.indices) {
            val along = if (across) h[i] else v[i]
            val narrow = if (across) tall[i] else broad[i]
            if (along >= length && narrow <= wide && narrow >= width / 2) bytes[i] = ON
        }
        val mask = mats.own(Mat(space.rows, space.cols, CvType.CV_8U))
        mask.put(0, 0, bytes)
        // The opening drops door throats: long one way, narrow the other, for only a few pixels.
        val open = if (across) mats.kernel(run, 1) else mats.kernel(1, run)
        val close = if (across) mats.kernel(gap, 1) else mats.kernel(1, gap)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, open)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, close)
        return mask
    }

    val joined = strip(across = true)
    Core.bitwise_or(joined, strip(across = false), joined)
    val join = (width * STRIP_JOIN).toInt() or 1
    val ellipse = mats.kernel(join, shape = Imgproc.MORPH_ELLIPSE)
    Imgproc.morphologyEx(joined, joined, Imgproc.MORPH_CLOSE, ellipse)
    val pixels = ByteArray(h.size).also { joined.get(0, 0, it) }
    for (i in pixels.indices) if (space.blocked[i] != 0.toByte()) pixels[i] = 0
    joined.put(0, 0, pixels)
    val labels = mats.mat()
    val stats = mats.mat()
    val count = Imgproc.connectedComponentsWithStats(joined, labels, stats, mats.mat())
    if (count <= 1) return joined
    val areas = DoubleArray(count) { if (it == 0) 0.0 else stats.get(it, Imgproc.CC_STAT_AREA)[0] }
    val limit = KEEP_FRACTION * areas.max()
    val ids = IntArray(h.size).also { labels.get(0, 0, it) }
    for (i in pixels.indices) pixels[i] = if (ids[i] != 0 && areas[ids[i]] >= limit) ON else 0
    joined.put(0, 0, pixels)
    return joined
}
