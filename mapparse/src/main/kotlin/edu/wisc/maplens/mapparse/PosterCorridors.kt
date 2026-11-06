package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.nearestEdge
import kotlin.math.hypot
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

internal fun routeMask(hsv: Mat, mask: Mat, mats: Mats) {
    val red = mats.mat()
    Core.inRange(hsv, Scalar(0.0, 71.0, 81.0), Scalar(19.0, 255.0, 255.0), mask)
    Core.inRange(hsv, Scalar(166.0, 71.0, 81.0), Scalar(179.0, 255.0, 255.0), red)
    Core.bitwise_or(mask, red, mask)
}

/** Retry visible, faint paint around an isolated star when its corridor is missing. */
internal fun recoverMarkerCorridor(
    hsv: Mat,
    routes: Mat,
    graph: FloorGraph,
    stars: List<PixelPoint>,
): FloorGraph = Mats().use { mats ->
    val star = stars.singleOrNull() ?: return graph
    if ((graph.nearestEdge(star)?.distancePx ?: Double.POSITIVE_INFINITY) <= 40.0) return graph
    val left = (star.x - 150).toInt().coerceAtLeast(0)
    val top = (star.y - 150).toInt().coerceAtLeast(0)
    val right = (star.x + 151).toInt().coerceAtMost(hsv.cols())
    val bottom = (star.y + 151).toInt().coerceAtMost(hsv.rows())
    val region = mats.own(hsv.submat(top, bottom, left, right))
    val faint = mats.mat()
    val red = mats.mat()
    Core.inRange(region, Scalar(0.0, 60.0, 81.0), Scalar(19.0, 255.0, 255.0), faint)
    Core.inRange(region, Scalar(166.0, 60.0, 81.0), Scalar(179.0, 255.0, 255.0), red)
    Core.bitwise_or(faint, red, faint)
    val repaired = mats.own(routes.clone())
    val destination = mats.own(repaired.submat(top, bottom, left, right))
    Core.bitwise_or(destination, faint, destination)
    val candidate = PosterCorridors().extract(repaired, stars)
    val distance = candidate.nearestEdge(star)?.distancePx ?: Double.POSITIVE_INFINITY
    if (distance <= 25.0 &&
        candidate.copy(startNodeId = candidate.nodes.keys.first()).validate().isEmpty()
    ) {
        repaired.copyTo(routes)
        candidate
    } else {
        graph
    }
}

internal class PosterCorridors {
    private data class Params(
        val symbolSize: Int = 11,
        val routeWidth: Int = 9,
        val close: Int = 21,
        val keepFraction: Double = 0.25,
        val spurLength: Double = 60.0,
        val markerSpurLength: Double = 90.0,
        val mergeRadius: Double = 60.0,
        val refineNear: Double = 20.0,
        val refineFar: Double = 150.0,
        val refineMaxMove: Double = 80.0,
    )

    private data class Path(val from: Int, val to: Int, val points: List<PixelPoint>) {
        val length = pathLength(points)

        fun from(node: Int): List<PixelPoint> = if (from == node) points else points.asReversed()

        fun other(node: Int): Int = if (from == node) to else from
    }

    private val params = Params()
    private val nodes = linkedMapOf<Int, PixelPoint>()
    private val paths = mutableListOf<Path>()

    fun extract(
        routes: Mat,
        stars: List<PixelPoint> = emptyList(),
    ): FloorGraph = Mats().use { mats ->
        val width = routes.cols()
        val height = routes.rows()
        val mask = corridorMask(routes, mats)
        val padded = mats.mat()
        Core.copyMakeBorder(mask, padded, 1, 1, 1, 1, Core.BORDER_CONSTANT, Scalar(0.0))
        val stride = width + 2
        val pixels = ByteArray(stride * (height + 2))
        padded.get(0, 0, pixels)
        trace(thin(pixels, stride), stride)
        prune(stars)
        merge()
        contract()
        refine(mats)
        val degree = degree()
        val used = nodes.filterKeys { it in degree }
        val outputNodes = used.map { (id, point) ->
            val type = if (degree.getValue(id) >= 3) NodeType.JUNCTION else NodeType.DEAD_END
            Node("n$id", point, type)
        }.associateBy { it.id }
        val input = mats.own(MatOfPoint2f())
        val simple = mats.own(MatOfPoint2f())
        val edges = paths.mapIndexedNotNull { i, path ->
            input.fromList(path.points.map { Point(it.x, it.y) })
            Imgproc.approxPolyDP(input, simple, 2.0, false)
            val points = simple.toList().map { PixelPoint(it.x, it.y) }.toMutableList()
            if (points.size < 2) return@mapIndexedNotNull null
            points[0] = used.getValue(path.from)
            points[points.lastIndex] = used.getValue(path.to)
            val length = pathLength(points)
            if (length > 0) {
                Edge("e$i", "n${path.from}", "n${path.to}", points, length)
            } else {
                null
            }
        }
        val incident = edges.flatMap { listOf(it.from, it.to) }.toSet()
        FloorGraph(
            "engineering-hall",
            "3",
            outputNodes.filterKeys { it in incident },
            edges,
            emptyList(),
            "",
        )
    }

    private fun corridorMask(routes: Mat, mats: Mats): Mat {
        val mask = mats.mat()
        val blobs = mats.mat()
        val clean = mats.mat()
        val disk = mats.kernel(params.symbolSize, shape = Imgproc.MORPH_ELLIPSE)
        Imgproc.morphologyEx(routes, blobs, Imgproc.MORPH_OPEN, disk)
        Imgproc.dilate(blobs, blobs, mats.kernel(7))
        Core.bitwise_not(blobs, blobs)
        Core.bitwise_and(routes, blobs, clean)
        // Long runs preserve painted route lines through junction blobs and arrow tails.
        for ((width, height) in listOf(61 to 1, 1 to 61)) {
            Imgproc.morphologyEx(routes, blobs, Imgproc.MORPH_OPEN, mats.kernel(width, height))
            Core.bitwise_or(clean, blobs, clean)
        }
        Imgproc.dilate(clean, mask, mats.kernel(params.routeWidth))
        val close = mats.kernel(params.close, shape = Imgproc.MORPH_ELLIPSE)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, close)
        val labels = mats.mat()
        val stats = mats.mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, mats.mat())
        if (count <= 1) return mask
        val areas = DoubleArray(count) { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
        areas[0] = 0.0
        val minArea = params.keepFraction * areas.max()
        val components = IntArray(mask.rows() * mask.cols())
        labels.get(0, 0, components)
        val pixels = ByteArray(components.size)
        clean.get(0, 0, pixels)
        val onRoute = BooleanArray(count)
        for (i in pixels.indices) {
            if (pixels[i] != 0.toByte()) onRoute[components[i]] = true
        }
        val keep = BooleanArray(count) { it != 0 && areas[it] >= minArea && onRoute[it] }
        for (i in pixels.indices) pixels[i] = if (keep[components[i]]) -1 else 0
        mask.put(0, 0, pixels)
        return mask
    }

    private fun trace(skeleton: Set<Int>, stride: Int) {
        val offsets = intArrayOf(
            -stride - 1,
            -stride,
            -stride + 1,
            -1,
            1,
            stride - 1,
            stride,
            stride + 1
        )
        fun neighbours(pixel: Int) = offsets.map { pixel + it }.filter { it in skeleton }
        fun point(pixel: Int) = PixelPoint(
            (pixel % stride - 1).toDouble(),
            (pixel / stride - 1).toDouble()
        )

        // Resolve competing spur/merge candidates in a stable bottom-to-top order.
        val special = skeleton.filter { neighbours(it).size != 2 }.sortedDescending().toSet()
        val owners = hashMapOf<Int, Int>()
        for (pixel in special) {
            if (pixel in owners) continue
            val id = nodes.size
            val group = mutableListOf(pixel)
            owners[pixel] = id
            var index = 0
            while (index < group.size) {
                for (other in neighbours(group[index++])) {
                    if (other in special && owners.putIfAbsent(other, id) == null) group += other
                }
            }
            nodes[id] = PixelPoint(
                group.map(::point).map { it.x }.average(),
                group.map(::point).map { it.y }.average()
            )
        }
        val visited = hashSetOf<Pair<Int, Int>>()
        val traced = special.toMutableSet()

        fun walk(start: Int, first: Int) {
            if (owners[start] == owners[first] || !visited.add(start to first)) return
            var previous = start
            var current = first
            val points = mutableListOf(point(start), point(first))
            while (current !in owners) {
                traced += current
                val next = neighbours(current).first { it != previous }
                previous = current
                current = next
                points += point(current)
            }
            visited += current to previous
            val a = owners.getValue(start)
            val b = owners.getValue(current)
            if (a != b || points.size >= 10) paths += Path(a, b, points)
        }

        for (pixel in special) for (next in neighbours(pixel)) walk(pixel, next)
        // An isolated all-degree-two loop has no natural junction.
        for (pixel in skeleton) {
            if (!traced.add(pixel)) continue
            val id = nodes.size
            nodes[id] = point(pixel)
            owners[pixel] = id
            walk(pixel, neighbours(pixel).first())
        }
    }

    private fun degree(): Map<Int, Int> = paths.flatMap { listOf(it.from, it.to) }
        .groupingBy { it }.eachCount()

    private fun prune(stars: List<PixelPoint>) {
        while (true) {
            val degree = degree()
            val spur = paths.indexOfFirst {
                val fromEnd = degree[it.from] == 1
                if (fromEnd == (degree[it.to] == 1)) return@indexOfFirst false
                // Star paint matches the route colour, so a stub into a star is not a corridor.
                val end = nodes.getValue(if (fromEnd) it.from else it.to)
                val atStar = stars.any { star -> star.distanceTo(end) <= 40.0 }
                it.length < if (atStar) params.markerSpurLength else params.spurLength
            }
            if (spur < 0) return
            paths.removeAt(spur)
        }
    }

    private fun merge() {
        while (true) {
            val index = paths.indexOfFirst { it.from != it.to && it.length < params.mergeRadius }
            if (index < 0) break
            val (a, b) = paths.removeAt(index)
            val u = nodes.getValue(a)
            val v = nodes.getValue(b)
            nodes[a] = PixelPoint((u.x + v.x) / 2, (u.y + v.y) / 2)
            nodes.remove(b)
            paths.replaceAll {
                it.copy(from = if (it.from == b) a else it.from, to = if (it.to == b) a else it.to)
            }
        }
        paths.removeAll { it.from == it.to && it.length <= 4 * params.mergeRadius }
    }

    private fun contract() {
        while (true) {
            val joint = degree().entries.firstNotNullOfOrNull { (id, degree) ->
                if (degree != 2) return@firstNotNullOfOrNull null
                val incident = paths.filter { it.from == id || it.to == id }
                if (incident.size == 2 && incident[0].other(id) != incident[1].other(id)) {
                    id to incident
                } else {
                    null
                }
            } ?: return
            val (id, incident) = joint
            val (a, b) = incident
            val points = a.from(id).asReversed() + b.from(id).drop(1)
            paths.removeAll(incident.toSet())
            paths += Path(a.other(id), b.other(id), points)
            nodes.remove(id)
        }
    }

    private fun refine(mats: Mats) {
        for ((id, degree) in degree()) {
            if (degree < 3) continue
            var xx = 0.0
            var xy = 0.0
            var yy = 0.0
            var px = 0.0
            var py = 0.0
            for (path in paths) {
                for (forward in listOf(true, false)) {
                    if ((if (forward) path.from else path.to) != id) continue
                    val points = if (forward) path.points else path.points.asReversed()
                    var travelled = 0.0
                    val arm = mutableListOf<Point>()
                    for ((a, b) in points.zipWithNext()) {
                        travelled += a.distanceTo(b)
                        if (travelled > params.refineFar) break
                        if (travelled >= params.refineNear) arm += Point(b.x, b.y)
                    }
                    if (arm.size < 8) continue
                    val (vx, vy, x, y) = fitLine(arm, Imgproc.DIST_L2, mats)
                    val nx = -vy
                    val ny = vx
                    xx += nx * nx
                    xy += nx * ny
                    yy += ny * ny
                    val projected = nx * x + ny * y
                    px += nx * projected
                    py += ny * projected
                }
            }
            val determinant = xx * yy - xy * xy
            val largest = (xx + yy + hypot(xx - yy, 2 * xy)) / 2
            if (determinant <= 0 || largest * largest > 50 * determinant) continue
            val point = PixelPoint(
                (yy * px - xy * py) / determinant,
                (xx * py - xy * px) / determinant
            )
            if (point.distanceTo(nodes.getValue(id)) <= params.refineMaxMove) nodes[id] = point
        }
    }
}

// scikit-image 0.26 _fast_skeletonize table, indexed clockwise from northwest.
// A value of 1 or 2 removes a pixel in that phase; 3 removes it in either phase.
private val thinningRules = intArrayOf(
    0, 0, 0, 1, 0, 0, 1, 3, 0, 0, 3, 1, 1, 0, 1, 3,
    0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 2, 0, 3, 0, 3, 3,
    0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 3, 0, 2, 2,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    2, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0,
    3, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 3, 0, 2, 0,
    0, 0, 3, 1, 0, 0, 1, 3, 0, 0, 0, 0, 0, 0, 0, 1,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    2, 3, 1, 3, 0, 0, 1, 3, 0, 0, 0, 0, 0, 0, 0, 1,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    2, 3, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
    3, 3, 0, 1, 0, 0, 0, 0, 2, 2, 0, 0, 2, 0, 0, 0,
)

/** Apply each thinning phase in parallel so removing a pixel cannot affect its neighbours. */
internal fun thin(pixels: ByteArray, stride: Int): Set<Int> {
    val offsets = intArrayOf(
        -stride - 1,
        -stride,
        -stride + 1,
        1,
        stride + 1,
        stride,
        stride - 1,
        -1,
    )
    val foreground = pixels.indices.filterTo(linkedSetOf()) { pixels[it] != 0.toByte() }
    val remove = mutableListOf<Int>()
    do {
        checkCancellation()
        var changed = false
        for (phase in 1..2) {
            for (i in foreground) {
                var mask = 0
                for (bit in offsets.indices) {
                    if (pixels[i + offsets[bit]] != 0.toByte()) mask = mask or (1 shl bit)
                }
                val rule = thinningRules[mask]
                if (rule == phase || rule == 3) remove += i
            }
            changed = changed || remove.isNotEmpty()
            for (i in remove) {
                pixels[i] = 0
                foreground.remove(i)
            }
            remove.clear()
        }
    } while (changed)
    return foreground
}

internal fun pathLength(points: List<PixelPoint>): Double =
    points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
