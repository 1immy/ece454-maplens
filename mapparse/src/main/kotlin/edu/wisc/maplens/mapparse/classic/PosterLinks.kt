package edu.wisc.maplens.mapparse.classic

import edu.wisc.maplens.model.PixelPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

private const val LINK_CLEAR = 0.6 // a link needs this many corridor widths of clear space
private const val LINK_REACH = 12.0 // a link joins corridors at most this many widths apart
private const val LINK_FROM = 2.0 // edges shorter than this many widths start no links
private const val OPEN_RUN = 4.0 // a line straight this many widths carries on along its hallway
private const val LINK_LANE = 0.35 // share of a corridor width between a link's lanes
private const val LINK_ARM = 30 // px of line used to read a corner's or an end's direction
private const val BEND_COSINE = 0.5 // a line turning more than 60 degrees has a corner
private const val AXIS_TAKEN = 0.7 // a corridor within 45 degrees of an axis takes that direction

// Only a line within 20 degrees of an axis carries straight on. Paint bends diagonally around
// symbols, and that bend is no corridor.
private const val AXIS_STRAIGHT = 0.94
private const val STUB_REACH = 4.0 // an unpainted corridor end reaches at most this many widths
private const val STUB_MIN = 0.6 // shorter stubs are the space beside a line, not a corridor
private const val STUB_WIDE = 1.7 // a stub is at most this many widths between its walls
private const val WALK_MARGIN = 0.5f // a walk between pieces keeps this many widths from walls
private const val WALK_REACH = 25.0 // and is at most this many widths long
private const val NECK_MARGIN = 0.3f // widths from any wall when walking with door gaps open
private const val NECK_REACH = 12.0 // such a walk is at most this many widths long
private const val WALK_PIECE = 4.0 // pieces shorter than this many widths are not worth joining

// A paint end carries on to paint it points at this many widths on, if straight ahead.
private const val FACING_REACH = 3.0

// A paint end joins a line the photo's route colour reaches this many widths away; 4 to 8 give the
// same graphs on every image.
private const val COLOUR_REACH = 6.0
internal const val STRAIGHT_COSINE = 0.9 // an end within about 25 degrees of its line is straight

private const val UNSEEN = -2
private const val SETTLED = -3

private val AXES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

internal fun degree(paths: List<CorridorPath>): Map<Int, Int> =
    paths.flatMap { listOf(it.from, it.to) }.groupingBy { it }.eachCount()

/** Each node's network, and each network's total length, keyed by its root node. */
internal fun networks(
    nodes: Map<Int, PixelPoint>,
    paths: List<CorridorPath>,
): Pair<Map<Int, Int>, Map<Int, Double>> {
    val parent = hashMapOf<Int, Int>()
    for (node in nodes.keys) parent[node] = node
    fun find(start: Int): Int {
        var node = start
        while (parent.getValue(node) != node) node = parent.getValue(node)
        return node
    }
    for (path in paths) parent[find(path.from)] = find(path.to)
    val lengths = hashMapOf<Int, Double>()
    for (path in paths) {
        val root = find(path.from)
        lengths[root] = (lengths[root] ?: 0.0) + path.length
    }
    return nodes.keys.associateWith(::find) to lengths
}

/** Heading of a polyline leaving its first point, from about 30 px of its length. */
internal fun armDirection(points: List<PixelPoint>): PixelPoint? {
    var travelled = 0.0
    var last = points[0]
    for (i in 1 until points.size) {
        travelled += points[i - 1].distanceTo(points[i])
        last = points[i]
        if (travelled >= LINK_ARM) break
    }
    if (travelled == 0.0) return null
    val dx = last.x - points[0].x
    val dy = last.y - points[0].y
    val norm = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
    return PixelPoint(dx / norm, dy / norm)
}

/** Indices where a polyline turns sharply, sharpest first within each neighbourhood. */
private fun bends(points: List<PixelPoint>): List<Int> {
    val turns = mutableListOf<Pair<Double, Int>>()
    for (i in LINK_ARM until points.size - LINK_ARM) {
        val ax = points[i].x - points[i - LINK_ARM].x
        val ay = points[i].y - points[i - LINK_ARM].y
        val bx = points[i + LINK_ARM].x - points[i].x
        val by = points[i + LINK_ARM].y - points[i].y
        val norm = hypot(ax, ay) * hypot(bx, by)
        val cosine = if (norm > 0) (ax * bx + ay * by) / norm else 1.0
        if (cosine < BEND_COSINE) turns += cosine to i
    }
    val chosen = mutableListOf<Int>()
    for ((_, i) in turns.sortedBy { it.first }) {
        if (chosen.all { abs(i - it) > 2 * LINK_ARM }) chosen += i
    }
    return chosen
}

private fun straight(start: PixelPoint, end: PixelPoint): List<PixelPoint> {
    val count = maxOf(1, start.distanceTo(end).toInt())
    return (0..count).map {
        PixelPoint(
            start.x + (end.x - start.x) * it / count,
            start.y + (end.y - start.y) * it / count,
        )
    }
}

/** Adds the corridors that paint or the strip test missed, using the walls. */
internal class CorridorLinks(
    private val nodes: MutableMap<Int, PixelPoint>,
    private val paths: MutableList<CorridorPath>,
    private val space: FreeSpace,
) {
    private val width = space.width
    private val tolerance = maxOf(4, (width * 0.25).toInt())

    private class Arm(val direction: PixelPoint, val long: Boolean)

    private class Origin(
        val position: PixelPoint,
        val arms: List<Arm>,
        val own: List<Int>,
        val node: Int?,
        val at: Int?,
    )

    private enum class Kind { LINE, WALL }

    private class Found(val kind: Kind, val travelled: Int, val hit: Int)

    private fun lineLabels(): IntArray = Mats().use { mats ->
        val labels = mats.own(Mat.zeros(space.rows, space.cols, CvType.CV_32S))
        for ((index, path) in paths.withIndex()) {
            val line = mats.own(MatOfPoint())
            line.fromList(
                path.points.map { Point(it.x.toInt().toDouble(), it.y.toInt().toDouble()) },
            )
            Imgproc.polylines(
                labels,
                listOf(line),
                false,
                Scalar(index + 1.0),
                tolerance * 2 + 1,
            )
        }
        IntArray(space.rows * space.cols).also { labels.get(0, 0, it) }
    }

    private fun clearAcross(x: Int, y: Int, dx: Int, dy: Int, cap: Int): Int {
        var total = 1
        for (sign in intArrayOf(1, -1)) {
            for (step in 1..cap) {
                val px = x + sign * step * dy
                val py = y + sign * step * dx
                if (!space.inside(px, py) || space.isBlocked(px, py)) break
                total++
            }
        }
        return total
    }

    /**
     * Walk along an axis to another line, or to a wall that ends an unpainted corridor. That stretch
     * must look narrow, unless it carries on from the end of a long straight line ([openEnd]): room
     * walls along a hallway are drawn thin and broken by doors, and often don't read as walls.
     */
    private fun cast(
        labels: IntArray,
        x0: Int,
        y0: Int,
        axis: Pair<Int, Int>,
        own: List<Int>,
        shortest: Int,
        openEnd: Boolean,
    ): Found? {
        val (dx, dy) = axis
        val clear = (width * LINK_CLEAR).toInt()
        val wide = (width * STUB_WIDE).toInt()
        val near = width * 2
        val reach = (width * LINK_REACH).toInt()
        val offsets = intArrayOf((width * 0.3).toInt(), (width * 0.7).toInt())
        var beside = 0
        var narrow = 0
        var steps = 0
        var ended = false
        for (t in 1..reach) {
            val x = x0 + dx * t
            val y = y0 + dy * t
            if (!space.inside(x, y) || space.isBlocked(x, y)) {
                ended = true
                break
            }
            val hit = labels[y * space.cols + x] - 1
            if (hit >= 0 && (hit !in own || t > near)) {
                val runsBeside = steps > 0 && beside >= 0.5 * steps
                return if (t >= shortest && !runsBeside) Found(Kind.LINE, t, hit) else null
            }
            val across = clearAcross(x, y, dx, dy, wide)
            if (across < clear) return null
            steps++
            if (across <= wide) narrow++
            val sees = offsets.any { offset ->
                intArrayOf(1, -1).any { side ->
                    val sx = x + side * offset * dy
                    val sy = y + side * offset * dx
                    space.inside(sx, sy) && labels[sy * space.cols + sx].let {
                        it != 0 && it - 1 !in own
                    }
                }
            }
            if (sees) beside++
        }
        if (!ended) return null
        val runsBeside = steps > 0 && beside >= 0.5 * steps
        if (runsBeside || steps < width * STUB_MIN || steps > width * STUB_REACH) return null
        if (narrow < 0.7 * steps && !openEnd) return null
        return Found(Kind.WALL, steps, -1)
    }

    /** Whether the line ending at [node] keeps to the axis for OPEN_RUN widths back from its end. */
    private fun runsStraight(node: Int, index: Int, dx: Int, dy: Int): Boolean {
        val start = nodes.getValue(node)
        for (point in paths[index].from(node)) {
            val x = point.x - start.x
            val y = point.y - start.y
            if (abs(x * dy - y * dx) > width * LINK_LANE) return false
            if (abs(x * dx + y * dy) >= width * OPEN_RUN) return true
        }
        return false
    }

    private fun splitPath(index: Int, at: Int): Int {
        val path = paths[index]
        val node = nodes.keys.max() + 1
        nodes[node] = path.points[at]
        paths[index] = CorridorPath(path.from, node, path.points.subList(0, at + 1))
        paths += CorridorPath(node, path.to, path.points.subList(at, path.points.size))
        return node
    }

    /** Node on a path nearest the point: its existing end if close, otherwise a split. */
    private fun attach(index: Int, point: PixelPoint, reach: Double): Pair<Int, PixelPoint> {
        val path = paths[index]
        for (node in listOf(path.from, path.to)) {
            val position = nodes.getValue(node)
            if (position.distanceTo(point) <= reach) return node to position
        }
        val at = path.points.indices.minBy { path.points[it].distanceTo(point) }
        if (at == 0) return path.from to nodes.getValue(path.from)
        if (at == path.points.lastIndex) return path.to to nodes.getValue(path.to)
        val position = path.points[at]
        return splitPath(index, at) to position
    }

    private fun origins(shortest: Double): List<Origin> {
        val touching = hashMapOf<Int, MutableList<Int>>()
        val arms = hashMapOf<Int, MutableList<Arm>>()
        for ((index, path) in paths.withIndex()) {
            val ends = listOf(path.from to path.points, path.to to path.points.asReversed())
            for ((node, line) in ends) {
                touching.getOrPut(node) { mutableListOf() } += index
                armDirection(line)?.let {
                    arms.getOrPut(node) { mutableListOf() } += Arm(it, path.length >= shortest)
                }
            }
        }
        val out = mutableListOf<Origin>()
        for ((node, position) in nodes) {
            val nodeArms = arms[node] ?: continue
            out += Origin(position, nodeArms, touching.getValue(node), node, null)
        }
        for ((index, path) in paths.withIndex()) {
            for (at in bends(path.points)) {
                val before = path.points.subList(0, at + 1)
                val after = path.points.subList(at, path.points.size)
                // Paint rounds its corners, so each arm's heading is read past the curve.
                val found = listOfNotNull(
                    armDirection(before.subList(0, at - LINK_ARM + 1).asReversed())?.let {
                        Arm(it, pathLength(before) >= shortest)
                    },
                    armDirection(after.subList(LINK_ARM, after.size))?.let {
                        Arm(it, pathLength(after) >= shortest)
                    },
                )
                out += Origin(path.points[at], found, listOf(index), null, at)
            }
        }
        return out
    }

    private fun clearRun(x0: Int, y0: Int, dx: Int, dy: Int, length: Int): Boolean {
        val clear = (width * LINK_CLEAR).toInt()
        val cap = (width * STUB_WIDE).toInt()
        for (t in 1..length) {
            val x = x0 + dx * t
            val y = y0 + dy * t
            if (!space.inside(x, y) || space.isBlocked(x, y)) return false
            if (clearAcross(x, y, dx, dy, cap) < clear) return false
        }
        return true
    }

    private class End(val node: Int, val at: PixelPoint, val dx: Int, val dy: Int)

    private fun corridorEnds(): List<End> = degree(paths).mapNotNull { (node, count) ->
        if (count != 1) return@mapNotNull null
        val path = paths.first { it.from == node || it.to == node }
        val arm = armDirection(path.from(node)) ?: return@mapNotNull null
        // A line leaves the end going inward, so the corridor carries on the opposite way.
        AXES.firstOrNull { (dx, dy) -> -(dx * arm.x + dy * arm.y) > AXIS_TAKEN }?.let {
            End(node, nodes.getValue(node), it.first, it.second)
        }
    }

    /** Two corridor ends whose lines would cross, as at a corner the strips stop short of. */
    private fun addCornerLink(): Boolean {
        val reach = (width * LINK_REACH).toInt()
        val ends = corridorEnds()
        var bestTotal = Int.MAX_VALUE
        var bestPair: Pair<End, End>? = null
        var bestCorner: PixelPoint? = null
        for (i in ends.indices) {
            for (j in i + 1 until ends.size) {
                val a = ends[i]
                val b = ends[j]
                if (a.dx * b.dx + a.dy * b.dy != 0) continue
                val corner =
                    if (a.dx != 0) PixelPoint(b.at.x, a.at.y) else PixelPoint(a.at.x, b.at.y)
                val ta = round((corner.x - a.at.x) * a.dx + (corner.y - a.at.y) * a.dy).toInt()
                val tb = round((corner.x - b.at.x) * b.dx + (corner.y - b.at.y) * b.dy).toInt()
                if (ta !in 1..reach || tb !in 1..reach || ta + tb >= bestTotal) continue
                if (clearRun(a.at.x.toInt(), a.at.y.toInt(), a.dx, a.dy, ta) &&
                    clearRun(b.at.x.toInt(), b.at.y.toInt(), b.dx, b.dy, tb)
                ) {
                    bestTotal = ta + tb
                    bestPair = a to b
                    bestCorner = corner
                }
            }
        }
        val pair = bestPair ?: return false
        val corner = checkNotNull(bestCorner)
        val middle = nodes.keys.max() + 1
        nodes[middle] = corner
        for (end in listOf(pair.first, pair.second)) {
            paths += CorridorPath(end.node, middle, straight(end.at, corner))
        }
        return true
    }

    private class Link(val found: Found, val origin: Origin, val axis: Pair<Int, Int>)

    /** The shortest link still open: corridors carry straight on, from long arms only. */
    private fun shortestLink(labels: IntArray, used: Set<List<Int>>): Link? {
        val lanes = (width * LINK_LANE).toInt()
        var best: Link? = null
        for (origin in origins(width * LINK_FROM)) {
            val x0 = round(origin.position.x).toInt()
            val y0 = round(origin.position.y).toInt()
            val deadEnd = origin.node != null && origin.arms.size == 1
            val shortest = if (deadEnd) 1 else width
            for (axis in AXES) {
                val (dx, dy) = axis
                if (listOf(x0, y0, dx, dy) in used) continue
                fun along(arm: Arm) = dx * arm.direction.x + dy * arm.direction.y
                // Hooks into an EXIT sign lead nowhere.
                if (origin.arms.any { along(it) > AXIS_TAKEN } ||
                    origin.arms.none { it.long && -along(it) > AXIS_STRAIGHT }
                ) {
                    continue
                }
                val openEnd = deadEnd &&
                    origin.node?.let { runsStraight(it, origin.own.first(), dx, dy) } == true
                // Paint can hug a wall, so also try lanes either side of it.
                for (lane in intArrayOf(0, lanes, -lanes)) {
                    val lx = x0 + lane * dy
                    val ly = y0 + lane * dx
                    if (!space.inside(lx, ly) || space.isBlocked(lx, ly)) continue
                    val found =
                        cast(labels, lx, ly, axis, origin.own, shortest, openEnd) ?: continue
                    if (best == null || found.travelled < best.found.travelled) {
                        best = Link(found, origin, axis)
                    }
                    break
                }
            }
        }
        return best
    }

    /**
     * Paint never stops and starts again on its own; a sign or icon drawn over it cuts it in two. A
     * long line that ends pointing straight at the end of another, a short way on, carries on to
     * it. Short hooks, such as one into an EXIT sign, point nowhere useful, so they start nothing.
     */
    fun joinFacingEnds() {
        val reach = width * FACING_REACH
        while (true) {
            checkCancellation()
            val degree = degree(paths)
            var bestGap = Double.MAX_VALUE
            var best: Triple<Int, Int, Int>? = null
            for ((index, path) in paths.withIndex()) {
                if (path.length < width * LINK_FROM) continue
                for (node in listOf(path.from, path.to)) {
                    val arm = armDirection(path.from(node)) ?: continue
                    if (degree[node] != 1) continue
                    val start = nodes.getValue(node)
                    for ((other, target) in paths.withIndex()) {
                        val ends = listOf(target.from, target.to)
                            .filter { degree[it] == 1 }
                            .map { nodes.getValue(it) }
                        if (other == index || ends.isEmpty()) continue
                        for ((at, point) in target.points.withIndex()) {
                            val gap = start.distanceTo(point)
                            val dx = point.x - start.x
                            val dy = point.y - start.y
                            if (gap > 0 && gap <= reach && gap < bestGap &&
                                -(arm.x * dx + arm.y * dy) >= STRAIGHT_COSINE * gap &&
                                maxOf(abs(dx), abs(dy)) >= AXIS_STRAIGHT * gap &&
                                ends.minOf { it.distanceTo(point) } <= width
                            ) {
                                bestGap = gap
                                best = Triple(node, other, at)
                            }
                        }
                    }
                }
            }
            val (node, other, at) = best ?: return
            val target = paths[other]
            val end = when (at) {
                0 -> target.from
                target.points.lastIndex -> target.to
                else -> splitPath(other, at)
            }
            paths += CorridorPath(node, end, straight(nodes.getValue(node), nodes.getValue(end)))
        }
    }

    private class Reached(val steps: Int, val hit: Int, val at: PixelPoint)

    /** Shortest walk through route colour from [start] to a line other than [own], in [limit]. */
    private fun colourWalk(
        routes: ByteArray,
        labels: IntArray,
        start: PixelPoint,
        own: Int,
        limit: Int,
    ): Reached? {
        val cols = space.cols
        fun paint(x: Int, y: Int) = space.inside(x, y) && routes[y * cols + x] != 0.toByte()
        val x0 = round(start.x).toInt()
        val y0 = round(start.y).toInt()
        val seen = hashSetOf<Int>()
        val queue = ArrayDeque<IntArray>()
        for (y in y0 - tolerance..y0 + tolerance) {
            for (x in x0 - tolerance..x0 + tolerance) {
                val near = hypot((x - x0).toDouble(), (y - y0).toDouble()) <= tolerance
                if (near && paint(x, y)) {
                    seen += y * cols + x
                    queue.addLast(intArrayOf(x, y, 0))
                }
            }
        }
        while (queue.isNotEmpty()) {
            val (x, y, steps) = queue.removeFirst()
            if (steps >= limit) continue
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (dx == 0 && dy == 0 || !paint(nx, ny)) continue
                    val label = labels[ny * cols + nx]
                    if (label == own + 1 || !seen.add(ny * cols + nx)) continue
                    if (label != 0) {
                        val at = PixelPoint(nx.toDouble(), ny.toDouble())
                        return Reached(steps + 1, label - 1, at)
                    }
                    queue.addLast(intArrayOf(nx, ny, steps + 1))
                }
            }
        }
        return null
    }

    /**
     * A paint end joins the nearest line that the photo's route colour still reaches. Signs are cut
     * out of the paint, and a symbol's outline can chop the line beside it into twigs that pruning
     * drops, but the camera saw one line.
     */
    fun joinByColour(routes: ByteArray) {
        val limit = (width * COLOUR_REACH).toInt()
        while (true) {
            checkCancellation()
            val labels = lineLabels()
            val degree = degree(paths)
            var best: Pair<Reached, Int>? = null
            for ((index, path) in paths.withIndex()) {
                if (path.length < width * LINK_FROM) continue
                for (node in listOf(path.from, path.to)) {
                    if (degree[node] != 1) continue
                    val start = nodes.getValue(node)
                    val found = colourWalk(routes, labels, start, index, limit) ?: continue
                    if (best == null || found.steps < best.first.steps) best = found to node
                }
            }
            val (found, node) = best ?: return
            val (target, _) = attach(found.hit, found.at, tolerance * 1.5)
            val line = straight(nodes.getValue(node), nodes.getValue(target))
            paths += CorridorPath(node, target, line)
        }
    }

    /**
     * Straight, clear runs from a corner or an end that reach another line, or that end at a wall
     * a short way on. A door is too narrow to pass, so a link can't enter a room.
     */
    fun linkGaps() {
        val used = hashSetOf<List<Int>>()
        while (true) {
            checkCancellation()
            if (addCornerLink()) continue
            val link = shortestLink(lineLabels(), used) ?: return
            val x0 = round(link.origin.position.x).toInt()
            val y0 = round(link.origin.position.y).toInt()
            val (dx, dy) = link.axis
            used += listOf(x0, y0, dx, dy)
            val found = link.found
            val own = link.origin.own.first()
            // A corner looping back onto its own line is no new corridor.
            if (found.kind == Kind.LINE && found.hit == own && link.origin.at != null) continue
            var tip = PixelPoint(
                (x0 + dx * found.travelled).toDouble(),
                (y0 + dy * found.travelled).toDouble(),
            )
            val target = if (found.kind == Kind.LINE) {
                val (node, position) = attach(found.hit, tip, tolerance * 1.5)
                tip = position
                node
            } else {
                (nodes.keys.max() + 1).also { nodes[it] = tip }
            }
            val start = link.origin.node ?: splitPath(own, checkNotNull(link.origin.at))
            if (start != target) {
                paths += CorridorPath(start, target, straight(nodes.getValue(start), tip))
            }
        }
    }

    /**
     * Paint can split around a symbol into two lines between the same junctions. Keep the shorter
     * when the two never stray more than a corridor width apart.
     */
    fun dropParallel() {
        fun far(a: List<PixelPoint>, b: List<PixelPoint>): Double {
            val sampled = b.filterIndexed { index, _ -> index % 4 == 0 }
            return a.filterIndexed { index, _ -> index % 4 == 0 }
                .maxOf { p -> sampled.minOf { it.distanceTo(p) } }
        }
        while (true) {
            var drop = -1
            search@ for (i in paths.indices) {
                for (j in i + 1 until paths.size) {
                    val a = paths[i]
                    val b = paths[j]
                    if (setOf(a.from, a.to) != setOf(b.from, b.to)) continue
                    if (maxOf(far(a.points, b.points), far(b.points, a.points)) <= width) {
                        drop = if (b.length > a.length) j else i
                        break@search
                    }
                }
            }
            if (drop < 0) return
            paths.removeAt(drop)
        }
    }

    private fun clearance(blocked: ByteArray): FloatArray = Mats().use { mats ->
        val free = mats.own(Mat(space.rows, space.cols, CvType.CV_8U))
        val inverted = ByteArray(blocked.size) {
            if (blocked[it] != 0.toByte()) 0 else -1
        }
        free.put(0, 0, inverted)
        val distance = mats.mat()
        Imgproc.distanceTransform(free, distance, Imgproc.DIST_L2, 5)
        FloatArray(inverted.size).also { distance.get(0, 0, it) }
    }

    /** Breadth-first walk from the main network. Returns the pixel reached, or -1. */
    private fun walk(
        labels: IntArray,
        joins: (Int) -> Boolean,
        starts: (Int) -> Boolean,
        clearance: FloatArray,
        floor: Float,
        limit: Int,
        parents: IntArray,
    ): Int {
        val cols = space.cols
        val size = labels.size
        val queue = IntArray(size)
        val steps = IntArray(size)
        val moves = intArrayOf(1, -1, cols, -cols)
        parents.fill(UNSEEN)
        var head = 0
        var tail = 0
        for (i in 0 until size) {
            if (labels[i] != 0 && starts(labels[i] - 1)) {
                parents[i] = -1
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val at = queue[head++]
            if (steps[at] >= limit) continue
            val x = at % cols
            for (move in moves) {
                val next = at + move
                if (next !in 0 until size || parents[next] != UNSEEN) continue
                if ((move == 1 && x == cols - 1) || (move == -1 && x == 0)) continue
                val hit = labels[next]
                if (hit != 0 && joins(hit - 1)) {
                    parents[next] = at
                    return next
                }
                if (clearance[next] >= floor) {
                    parents[next] = at
                    steps[next] = steps[at] + 1
                    queue[tail++] = next
                } else {
                    parents[next] = SETTLED
                }
            }
        }
        return -1
    }

    /** Walk free space from the main network to a separate piece, where a person would go. */
    fun joinNetworks() {
        val clearance = clearance(space.blocked)
        // Closing door gaps can also pinch a narrow corridor shut, so last, walk a short way
        // unsealed.
        val openClearance = clearance(space.unsealed)
        val reach = (width * WALK_REACH).toInt()
        val neck = (width * NECK_REACH).toInt()
        val cols = space.cols
        val parents = IntArray(clearance.size)
        while (true) {
            checkCancellation()
            val (network, lengths) = networks(nodes, paths)
            if (lengths.size < 2) return
            val main = lengths.keys.maxBy { lengths.getValue(it) }
            val labels = lineLabels()
            val owner = paths.map { network.getValue(it.from) }
            val joins = { edge: Int -> lengths.getValue(owner[edge]) >= width * WALK_PIECE }
            val starts = { edge: Int -> owner[edge] == main }
            // The middle of a corridor first; squeeze past a wall only if nothing else fits.
            var goal = walk(labels, joins, starts, clearance, width * WALK_MARGIN, reach, parents)
            if (goal < 0) {
                val margin = width * NECK_MARGIN
                goal = walk(labels, joins, starts, openClearance, margin, neck, parents)
            }
            if (goal < 0) return
            val route = mutableListOf<PixelPoint>()
            var cursor = goal
            while (cursor >= 0) {
                route += PixelPoint((cursor % cols).toDouble(), (cursor / cols).toDouble())
                cursor = parents[cursor]
            }
            val (far, _) = attach(labels[goal] - 1, route.first(), tolerance * 1.5)
            val first = route.last()
            val index = lineLabels()[first.y.toInt() * cols + first.x.toInt()] - 1
            if (index < 0) return
            val (near, _) = attach(index, first, tolerance * 1.5)
            val line = listOf(nodes.getValue(far)) + route + nodes.getValue(near)
            paths += CorridorPath(far, near, line)
            // Overlapping lines can name the wrong piece. Stop rather than walk the same way again.
            if (networks(nodes, paths).second.size >= lengths.size) return
        }
    }
}
