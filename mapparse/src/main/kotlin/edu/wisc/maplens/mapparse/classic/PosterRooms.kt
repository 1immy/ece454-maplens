package edu.wisc.maplens.mapparse.classic

import android.graphics.Rect
import edu.wisc.maplens.model.PixelPoint
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

internal data class PosterReading(
    val text: String,
    val box: Rect,
    val confidence: Float,
    val possible: Boolean = false,
) {
    val center get() = PixelPoint(box.exactCenterX().toDouble(), box.exactCenterY().toDouble())
}

private val roomPattern = Regex("[0-9]{4}[A-Z]?")

internal fun normalizeRoom(text: String): String? = text.trim().trim(
    ',', ':', ';', '(', ')',
    '[', ']', '|', '\\'
).replace(Regex("[\\s.-]"), "").uppercase(Locale.ROOT)
    .takeIf(roomPattern::matches)

internal fun roomRowTokens(text: String): List<String> {
    val compact = text.trim().trim(',').uppercase(Locale.ROOT).filterNot {
        it.isWhitespace() || it == '|'
    }
    val tokens = roomPattern.findAll(compact).map { it.value }.toList()
    return tokens.takeIf { it.size >= 2 && it.joinToString("") == compact }.orEmpty()
}

internal fun standaloneRoomTokens(text: String): List<String> {
    val words = text.uppercase(Locale.ROOT).split(Regex("[\\s|]+")).filter(String::isNotEmpty)
    if (words.any { word -> word.none(Char::isDigit) || word.count(Char::isLetter) > 1 }) {
        return emptyList()
    }
    val rooms = words.filter(roomPattern::matches)
    return rooms.takeIf { it.size >= 2 }.orEmpty()
}

internal fun distinctRooms(readings: List<PosterReading>): List<PosterReading> {
    val kept = mutableListOf<PosterReading>()
    val ranked = readings.mapNotNull { reading ->
        val label = normalizeRoom(reading.text)
        // shortcut: assumes the third floor, read the floor from the poster when parsing others.
        if (reading.possible || label?.startsWith("3") != true || reading.confidence < 0.5f) {
            null
        } else {
            reading.copy(text = label)
        }
    }.sortedByDescending { it.confidence }
    for (reading in ranked) {
        if (kept.none {
                reading.center.distanceTo(it.center) <= 15.0 ||
                    (reading.text == it.text && overlap(reading.box, it.box) >= 0.5)
            }
        ) {
            kept += reading
        }
    }
    return kept.sortedWith(compareBy({ it.center.y }, { it.center.x }))
}

private fun overlap(a: Rect, b: Rect): Double {
    val area = minOf(a.width() * a.height(), b.width() * b.height())
    if (area <= 0) return 0.0
    val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
    val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
    return width.toDouble() * height / area
}

internal fun wallMask(bgr: Mat, routes: Mat, readings: List<PosterReading>): ByteArray =
    Mats().use { mats ->
        val dark = darkInk(bgr, mats)
        val bounds = Rect(0, 0, dark.cols(), dark.rows())
        for (reading in readings) {
            if (reading.possible || roomRowTokens(reading.text).isNotEmpty() ||
                standaloneRoomTokens(reading.text).isNotEmpty()
            ) {
                continue
            }
            if (reading.text.count { it.isLetterOrDigit() } < 2) continue
            val box = Rect(reading.box)
            if (!box.intersect(bounds)) continue
            val region = dark.submat(box.top, box.bottom, box.left, box.right)
            try {
                region.setTo(Scalar(0.0))
            } finally {
                region.release()
            }
        }
        dark.setTo(Scalar(0.0), routes)
        val walls = mats.mat()
        val vertical = mats.mat()
        Imgproc.morphologyEx(dark, walls, Imgproc.MORPH_OPEN, mats.kernel(25, 1))
        Imgproc.morphologyEx(dark, vertical, Imgproc.MORPH_OPEN, mats.kernel(1, 25))
        Core.bitwise_or(walls, vertical, walls)
        val support = mats.mat()
        Imgproc.dilate(walls, support, mats.kernel(17, shape = Imgproc.MORPH_ELLIPSE))
        Core.bitwise_and(dark, support, support)
        Core.bitwise_or(walls, support, walls)
        ByteArray(walls.cols() * walls.rows()).also { walls.get(0, 0, it) }
    }

internal fun interpolate(a: PixelPoint, b: PixelPoint, fraction: Double): PixelPoint =
    PixelPoint(a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y))

internal data class CorridorConnector(val target: PixelPoint, val length: Double)

/** MCP_Geometric's eight-neighbour, mean-endpoint cost search within the local image crop. */
internal fun corridorPath(
    origin: PixelPoint,
    samples: List<PixelPoint>,
    walls: ByteArray,
    width: Int,
): CorridorConnector? {
    val limit = 350.0
    val wallLimit = 6
    val height = walls.size / width
    if (origin.x < 0 || origin.y < 0 || origin.x >= width || origin.y >= height) return null
    val left = floor(origin.x - limit).toInt().coerceAtLeast(0)
    val top = floor(origin.y - limit).toInt().coerceAtLeast(0)
    val right = ceil(origin.x + limit + 1).toInt().coerceAtMost(width)
    val bottom = ceil(origin.y + limit + 1).toInt().coerceAtMost(height)
    val stride = right - left
    val rows = bottom - top
    val size = stride * rows
    fun cell(point: PixelPoint): Int {
        val x = (point.x - left).roundToInt().coerceIn(0, stride - 1)
        val y = (point.y - top).roundToInt().coerceIn(0, rows - 1)
        return y * stride + x
    }

    fun wall(index: Int): Boolean =
        walls[(index / stride + top) * width + index % stride + left] != 0.toByte()

    val nearby = samples.filter {
        it.x >= left && it.x < right && it.y >= top && it.y < bottom
    }
    if (nearby.isEmpty()) return null
    val goals = hashMapOf<Int, PixelPoint>()
    for (point in nearby) goals.putIfAbsent(cell(point), point)

    data class Step(val cell: Int, val cost: Double)
    val queue = PriorityQueue<Step> { a, b -> a.cost.compareTo(b.cost) }
    val costs = DoubleArray(size) { Double.POSITIVE_INFINITY }
    val lengths = FloatArray(size)
    val crossings = IntArray(size)
    val start = cell(origin)
    costs[start] = 0.0
    crossings[start] = if (wall(start)) 1 else 0
    queue += Step(start, 0.0)
    var popped = 0
    var wallPath: CorridorConnector? = null
    while (queue.isNotEmpty()) {
        if (popped++ % 1024 == 0) checkCancellation()
        val (index, cost) = queue.remove()
        if (cost != costs[index]) continue
        val goal = goals[index]
        if (goal != null) {
            if (crossings[index] <= wallLimit && lengths[index] <= limit) {
                wallPath = CorridorConnector(goal, lengths[index].toDouble())
            }
            break
        }
        val x = index % stride
        val y = index / stride
        val fromWall = wall(index)
        for (dy in -1..1) for (dx in -1..1) {
            if ((dx == 0 && dy == 0) || x + dx !in 0 until stride || y + dy !in 0 until rows) {
                continue
            }
            val next = index + dy * stride + dx
            val toWall = wall(next)
            val distance = if (dx == 0 || dy == 0) 1.0 else Math.sqrt(2.0)
            val unitCost = if (fromWall && toWall) 80.0 else if (fromWall || toWall) 40.5 else 1.0
            val total = cost + distance * unitCost
            if (total >= costs[next]) continue
            costs[next] = total
            lengths[next] = lengths[index] + distance.toFloat()
            crossings[next] = crossings[index] + if (toWall) 1 else 0
            queue += Step(next, total)
        }
    }

    // A few stray ink pixels at a doorway can otherwise steer rooms to the same distant opening.
    val directLimit = wallPath?.let { it.length - 8.0 } ?: limit
    val directWallLimit = if (wallPath == null) wallLimit else 2
    for (target in nearby.sortedBy { it.distanceTo(origin) }) {
        val distance = target.distanceTo(origin)
        if (distance > directLimit) break
        val steps = ceil(distance).toInt().coerceAtLeast(1)
        var count = 0
        var previous: PixelPoint? = null
        for (i in 0..steps) {
            val point = interpolate(origin, target, i.toDouble() / steps)
            val x = point.x.roundToInt().coerceIn(0, width - 1)
            val y = point.y.roundToInt().coerceIn(0, height - 1)
            val pixel = PixelPoint(x.toDouble(), y.toDouble())
            if (pixel != previous && walls[y * width + x] != 0.toByte()) count++
            previous = pixel
            if (count > directWallLimit) break
        }
        if (count <= directWallLimit) return CorridorConnector(target, distance)
    }
    return wallPath
}
