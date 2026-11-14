package edu.wisc.maplens.mapparse.classic

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.nearestEdge
import kotlin.math.hypot
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** Route colour, whose hue wraps around red: at least [minS] saturated and brighter than 80. */
private fun redHue(hsv: Mat, minS: Double, out: Mat, mats: Mats, wrapHue: Double = 166.0) {
    val upper = mats.mat()
    Core.inRange(hsv, Scalar(0.0, minS, 81.0), Scalar(19.0, 255.0, 255.0), out)
    Core.inRange(hsv, Scalar(wrapHue, minS, 81.0), Scalar(179.0, 255.0, 255.0), upper)
    Core.bitwise_or(out, upper, out)
}

internal fun routeMask(hsv: Mat, mask: Mat, mats: Mats) {
    redHue(hsv, 71.0, mask, mats)
    val notWalls = dropWallFringe(hsv, mask, mats)
    addFaintPaint(hsv, mask, notWalls, mats)
}

/**
 * Photos of a screen fringe walls with route colour, and those streaks trace as extra corridors.
 * Walls are dark and colourless, unlike paint. A streak on a wall goes only when solid paint runs
 * beside it, so a faint corridor with nothing stronger nearby survives. Returns what isn't wall.
 */
private fun dropWallFringe(hsv: Mat, mask: Mat, mats: Mats): Mat {
    val dark = mats.mat()
    Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(179.0, 69.0, 109.0), dark)
    // A phone photo can leave walls lighter than that, so a wall may also be grey and dark for its
    // surroundings.
    val v = mats.mat()
    val local = mats.mat()
    Core.extractChannel(hsv, v, 2)
    Imgproc.medianBlur(v, local, 31)
    val s = mats.mat()
    Core.extractChannel(hsv, s, 1)
    val size = hsv.rows() * hsv.cols()
    val value = ByteArray(size).also { v.get(0, 0, it) }
    val around = ByteArray(size).also { local.get(0, 0, it) }
    val sat = ByteArray(size).also { s.get(0, 0, it) }
    val darkBytes = ByteArray(size).also { dark.get(0, 0, it) }
    for (i in 0 until size) {
        val contrast = (around[i].toInt() and 255) - (value[i].toInt() and 255)
        if (contrast > WALL_CONTRAST && (sat[i].toInt() and 255) < WALL_GREY_S) darkBytes[i] = -1
    }
    dark.put(0, 0, darkBytes)
    val walls = mats.mat()
    val vertical = mats.mat()
    Imgproc.morphologyEx(dark, walls, Imgproc.MORPH_OPEN, mats.kernel(WALL_RUN, 1))
    Imgproc.morphologyEx(dark, vertical, Imgproc.MORPH_OPEN, mats.kernel(1, WALL_RUN))
    Core.bitwise_or(walls, vertical, walls)
    Imgproc.dilate(walls, walls, mats.kernel(FRINGE_REACH))
    val notWalls = mats.mat()
    Core.bitwise_not(walls, notWalls)
    val nearPaint = mats.mat()
    Core.bitwise_and(mask, notWalls, nearPaint)
    Imgproc.dilate(nearPaint, nearPaint, mats.kernel(PAINT_REACH, shape = Imgproc.MORPH_ELLIPSE))
    Core.bitwise_and(walls, nearPaint, walls)
    Core.bitwise_not(walls, walls)
    Core.bitwise_and(mask, walls, mask)
    return notWalls
}

/**
 * Faint paint reads under the solid cutoff in stretches. It never counts beside a wall, where it
 * can't be told from fringe, and it must reach solid paint.
 */
private fun addFaintPaint(hsv: Mat, mask: Mat, notWalls: Mat, mats: Mats) {
    val faint = mats.mat()
    redHue(hsv, FAINT_S + 1.0, faint, mats)
    Core.bitwise_and(faint, notWalls, faint)
    val down = mats.mat()
    Imgproc.morphologyEx(faint, down, Imgproc.MORPH_OPEN, mats.kernel(1, FAINT_RUN))
    Imgproc.morphologyEx(faint, faint, Imgproc.MORPH_OPEN, mats.kernel(FAINT_RUN, 1))
    Core.bitwise_or(faint, down, faint)
    val grown = mats.mat()
    Core.bitwise_or(mask, faint, grown)
    Imgproc.dilate(grown, grown, mats.kernel(FAINT_LINK))
    val labels = mats.mat()
    val count = Imgproc.connectedComponents(grown, labels)
    val ids = IntArray(mask.rows() * mask.cols()).also { labels.get(0, 0, it) }
    val solid = ByteArray(ids.size).also { mask.get(0, 0, it) }
    val weak = ByteArray(ids.size).also { faint.get(0, 0, it) }
    val seeded = BooleanArray(count)
    for (i in ids.indices) if (solid[i] != 0.toByte()) seeded[ids[i]] = true
    seeded[0] = false
    for (i in ids.indices) if (weak[i] != 0.toByte() && seeded[ids[i]]) solid[i] = -1
    mask.put(0, 0, solid)
}

private const val FAINT_S = 45
private const val FAINT_RUN = 31
private const val FAINT_LINK = 15
private const val WALL_RUN = 25
private const val WALL_CONTRAST = 50 // or darker than this below the paper around it
private const val WALL_GREY_S = 45 // and greyer than this, since blur also darkens and greys paint

// A camera's colour fringe sits a few px out from a blurred wall edge.
private const val FRINGE_REACH = 11
private const val HOLE_AREA = 6000.0 // px; holes smaller than this in a corridor mask are filled
private const val SIGN_FILL = 7 // letters on a sign are holes at most this wide
private const val SIGN_AREA = 900 // px; signs are larger than any junction or extinguisher symbol
private const val SIGN_SIZE = 150 // px; and no side of a sign is longer than this
private const val SIGN_SOLID = 0.5 // and a sign fills at least this share of its box
private const val SIGN_THICK = 20 // px; a sign is a box at least this wide each way
private const val SIGN_LINES = 3 // paint leaving a blob in this many places is a junction
private const val SIGN_MARGIN = 7 // px around a sign removed with it, and out to the ring below
private const val SIGN_RING = 4 // px; leaving paint is read in a ring this wide beyond that
private const val PAINT_REACH = 61

/**
 * Share of route colour inside filled patches. Paint is thin lines. A camera can push pink room
 * fills into the route colour, and those fill most of the area around them.
 */
internal fun inFills(bgr: Mat, routes: Mat, mats: Mats): Double {
    val hsv = mats.mat()
    Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
    val reddish = mats.mat()
    redHue(hsv, FILL_S + 1.0, reddish, mats, wrapHue = 161.0)
    val cover = mats.mat()
    reddish.convertTo(cover, CvType.CV_32F, 1.0 / 255)
    Imgproc.blur(cover, cover, org.opencv.core.Size(FILL_WINDOW.toDouble(), FILL_WINDOW.toDouble()))
    val size = routes.rows() * routes.cols()
    val shares = FloatArray(size).also { cover.get(0, 0, it) }
    val paint = ByteArray(size).also { routes.get(0, 0, it) }
    var total = 0
    var filled = 0
    for (i in 0 until size) {
        if (paint[i] == 0.toByte()) continue
        total++
        if (shares[i] > FILL_COVER) filled++
    }
    return filled.toDouble() / maxOf(total, 1)
}

private const val FILL_S = 35 // pink room fills are at least this saturated
private const val FILL_WINDOW = 61 // px; a filled patch covers most of this window
private const val FILL_COVER = 0.45 // share of the window that is reddish in a filled patch

/** Retry visible, faint paint around an isolated star when its corridor is missing. */
internal fun recoverMarkerCorridor(
    hsv: Mat,
    routes: Mat,
    graph: FloorGraph,
    stars: List<PixelPoint>,
    bgr: Mat? = null,
): FloorGraph = Mats().use { mats ->
    val star = stars.singleOrNull() ?: return graph
    if ((graph.nearestEdge(star)?.distancePx ?: Double.POSITIVE_INFINITY) <= 40.0) return graph
    val left = (star.x - 150).toInt().coerceAtLeast(0)
    val top = (star.y - 150).toInt().coerceAtLeast(0)
    val right = (star.x + 151).toInt().coerceAtMost(hsv.cols())
    val bottom = (star.y + 151).toInt().coerceAtMost(hsv.rows())
    val region = mats.own(hsv.submat(top, bottom, left, right))
    val faint = mats.mat()
    redHue(region, 60.0, faint, mats)
    val repaired = mats.own(routes.clone())
    val destination = mats.own(repaired.submat(top, bottom, left, right))
    Core.bitwise_or(destination, faint, destination)
    val candidate = PosterCorridors().extract(repaired, stars, bgr)
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

internal class CorridorPath(val from: Int, val to: Int, val points: List<PixelPoint>) {
    val length = pathLength(points)

    fun from(node: Int): List<PixelPoint> = if (from == node) points else points.asReversed()

    fun other(node: Int): Int = if (from == node) to else from
}

internal class PosterCorridors {
    private data class Params(
        val symbolSize: Int = 11,
        val routeWidth: Int = 9,
        val close: Int = 21,
        val keepNetwork: Double = 0.25,
        val spurLength: Double = 60.0,
        val markerSpurLength: Double = 90.0,
        val mergeRadius: Double = 60.0,
        val refineNear: Double = 20.0,
        val refineFar: Double = 150.0,
        val refineMaxMove: Double = 80.0,
        val minRouteLength: Double = 1000.0,
        // Real paint is one network. A camera can push pink rooms into the route colour, but they
        // read as scattered pieces.
        val paintShare: Double = 0.5,
        // And sits mostly outside filled patches; pink rooms are filled.
        val fillShare: Double = 0.4,
        val stripSpur: Double = 2.5,
        val stripMerge: Double = 0.8,
        val stripEnd: Double = 1.5,
    )

    private val params = Params()
    private val nodes = linkedMapOf<Int, PixelPoint>()
    private val paths = mutableListOf<CorridorPath>()

    /**
     * Painted posters trace their paint. Posters without paint trace narrow free space. With [bgr],
     * corridors the first pass missed are linked in using the walls.
     */
    fun extract(
        routes: Mat,
        stars: List<PixelPoint> = emptyList(),
        bgr: Mat? = null,
    ): FloorGraph = Mats().use { mats ->
        var mask = corridorMask(routes, mats)
        traced(mask, stars, params.spurLength, params.mergeRadius, mats)
        var corridorWidth = 0.0
        if (bgr != null) {
            val lengths = networks(nodes, paths).second.values
            val total = lengths.sum()
            val painted = total >= params.minRouteLength &&
                (lengths.maxOrNull() ?: 0.0) >= params.paintShare * total &&
                inFills(bgr, routes, mats) < params.fillShare
            val space =
                freeSpace(bgr, if (painted) mask else null, if (painted) routes else null, mats)
            corridorWidth = space.width.toDouble()
            if (!painted) {
                nodes.clear()
                paths.clear()
                mask = stripMask(space, mats)
                traced(
                    mask,
                    emptyList(),
                    corridorWidth * params.stripSpur,
                    corridorWidth * params.stripMerge,
                    mats,
                )
                straightenEnds(corridorWidth * params.stripEnd)
            }
            if (paths.isNotEmpty()) {
                val links = CorridorLinks(nodes, paths, space)
                if (painted) {
                    links.joinFacingEnds()
                    val paint = ByteArray(routes.rows() * routes.cols())
                    routes.get(0, 0, paint)
                    links.joinByColour(paint)
                }
                links.linkGaps()
                links.joinNetworks()
                links.dropParallel()
                contract()
            }
        }
        keepNetworks()
        val degree = degree(paths)
        val used = nodes.filterKeys { it in degree }
        val outputNodes = used.map { (id, point) ->
            val type = if (degree.getValue(id) >= 3) NodeType.JUNCTION else NodeType.DEAD_END
            Node("n$id", point, type)
        }.associateBy { it.id }
        val tolerance = (corridorWidth * 0.2).coerceIn(2.0, 10.0)
        val edges = paths.mapIndexedNotNull { i, path ->
            val points = edgePolyline(
                used.getValue(path.from),
                used.getValue(path.to),
                path.points,
                tolerance,
                mats,
            )
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

    private fun traced(
        mask: Mat,
        stars: List<PixelPoint>,
        spur: Double,
        mergeRadius: Double,
        mats: Mats,
    ) {
        val filled = fillHoles(mask, mats)
        val padded = mats.mat()
        Core.copyMakeBorder(filled, padded, 1, 1, 1, 1, Core.BORDER_CONSTANT, Scalar(0.0))
        val stride = mask.cols() + 2
        val pixels = ByteArray(stride * (mask.rows() + 2))
        padded.get(0, 0, pixels)
        trace(thin(pixels, stride), stride)
        prune(stars, spur)
        merge(mergeRadius)
        contract()
        refine(mats)
    }

    /**
     * A copy with small holes filled, so a wide, patchy band thins to one line, not a ladder of
     * short loops. Holes around a room or a block are far larger and stay.
     */
    private fun fillHoles(mask: Mat, mats: Mats): Mat {
        val inverted = mats.mat()
        Core.bitwise_not(mask, inverted)
        val labels = mats.mat()
        val stats = mats.mat()
        val count = Imgproc.connectedComponentsWithStats(inverted, labels, stats, mats.mat(), 4)
        val rows = mask.rows()
        val cols = mask.cols()
        val ids = IntArray(rows * cols).also { labels.get(0, 0, it) }
        val small = BooleanArray(count) { stats.get(it, Imgproc.CC_STAT_AREA)[0] < HOLE_AREA }
        for (x in 0 until cols) {
            small[ids[x]] = false
            small[ids[(rows - 1) * cols + x]] = false
        }
        for (y in 0 until rows) {
            small[ids[y * cols]] = false
            small[ids[y * cols + cols - 1]] = false
        }
        val pixels = ByteArray(ids.size).also { mask.get(0, 0, it) }
        for (i in ids.indices) if (small[ids[i]]) pixels[i] = -1
        return mats.own(mask.clone()).also { it.put(0, 0, pixels) }
    }

    /** Drop pieces that never joined the main network, unless they are large themselves. */
    private fun keepNetworks() {
        val (network, lengths) = networks(nodes, paths)
        val limit = params.keepNetwork * (lengths.values.maxOrNull() ?: return)
        paths.removeAll { lengths.getValue(network.getValue(it.from)) < limit }
    }

    /**
     * Skeletons curl into the corner where a strip ends. Cut that curl so the end points along its
     * corridor and can link onward.
     */
    private fun straightenEnds(length: Double) {
        val degree = degree(paths)
        for (index in paths.indices) {
            for (forward in listOf(true, false)) {
                val path = paths[index]
                val tip = if (forward) path.from else path.to
                val line = if (forward) path.points else path.points.asReversed()
                if (degree[tip] != 1 || path.length <= 2 * length) continue
                var travelled = 0.0
                var cut = 0
                for (i in 1 until line.size) {
                    travelled += line[i - 1].distanceTo(line[i])
                    if (travelled >= length) {
                        cut = i
                        break
                    }
                }
                val inner = armDirection(line.subList(cut, line.size))
                val ox = line[cut].x - line[0].x
                val oy = line[cut].y - line[0].y
                val norm = hypot(ox, oy).takeIf { it > 0 } ?: 1.0
                if (inner != null && (inner.x * ox + inner.y * oy) / norm > STRAIGHT_COSINE) {
                    continue
                }
                val kept = line.subList(cut, line.size)
                nodes[tip] = kept.first()
                paths[index] = if (forward) {
                    CorridorPath(path.from, path.to, kept)
                } else {
                    CorridorPath(path.from, path.to, kept.asReversed())
                }
            }
        }
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
        val runs = mats.own(Mat.zeros(routes.size(), CvType.CV_8U))
        for ((width, height) in listOf(61 to 1, 1 to 61)) {
            Imgproc.morphologyEx(routes, blobs, Imgproc.MORPH_OPEN, mats.kernel(width, height))
            Core.bitwise_or(runs, blobs, runs)
        }
        Core.bitwise_or(clean, runs, clean)
        dropSigns(routes, clean, runs, disk, mats)
        Imgproc.dilate(clean, mask, mats.kernel(params.routeWidth))
        val close = mats.kernel(params.close, shape = Imgproc.MORPH_ELLIPSE)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, close)
        val labels = mats.mat()
        val stats = mats.mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, mats.mat())
        if (count <= 1) return mask
        val areas = DoubleArray(count) { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
        areas[0] = 0.0
        val minArea = KEEP_FRACTION * areas.max()
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

    /**
     * An EXIT sign is a red box with white letters. Filled in, it is a large compact blob, and its
     * edges are long enough to pass as runs, so it goes entirely. Blurred paint fills in too, but
     * runs far or stays thin. A route ends at a sign; thick paint where routes cross also fills in,
     * but routes leave it.
     */
    private fun dropSigns(routes: Mat, clean: Mat, runs: Mat, disk: Mat, mats: Mats) {
        val filled = mats.mat()
        Imgproc.morphologyEx(routes, filled, Imgproc.MORPH_CLOSE, mats.kernel(SIGN_FILL))
        Imgproc.morphologyEx(filled, filled, Imgproc.MORPH_OPEN, disk)
        val labels = mats.mat()
        val stats = mats.mat()
        val count = Imgproc.connectedComponentsWithStats(filled, labels, stats, mats.mat())
        fun stat(i: Int, field: Int) = stats.get(i, field)[0].toInt()
        val signs = BooleanArray(count) {
            val area = stats.get(it, Imgproc.CC_STAT_AREA)[0]
            val w = stat(it, Imgproc.CC_STAT_WIDTH)
            val h = stat(it, Imgproc.CC_STAT_HEIGHT)
            it != 0 && area >= SIGN_AREA && maxOf(w, h) <= SIGN_SIZE &&
                minOf(w, h) >= SIGN_THICK && area >= SIGN_SOLID * w * h
        }
        val reach = SIGN_MARGIN + SIGN_RING
        for (i in signs.indices) {
            if (!signs[i]) continue
            val x0 = maxOf(stat(i, Imgproc.CC_STAT_LEFT) - reach, 0)
            val y0 = maxOf(stat(i, Imgproc.CC_STAT_TOP) - reach, 0)
            val x1 = minOf(
                stat(i, Imgproc.CC_STAT_LEFT) + stat(i, Imgproc.CC_STAT_WIDTH) + reach,
                routes.cols(),
            )
            val y1 = minOf(
                stat(i, Imgproc.CC_STAT_TOP) + stat(i, Imgproc.CC_STAT_HEIGHT) + reach,
                routes.rows(),
            )
            val area = Rect(x0, y0, x1 - x0, y1 - y0)
            val blob = mats.mat()
            Core.compare(mats.own(labels.submat(area)), Scalar(i.toDouble()), blob, Core.CMP_EQ)
            val ring = mats.mat()
            val inner = mats.mat()
            Imgproc.dilate(blob, ring, mats.kernel(2 * reach + 1))
            Imgproc.dilate(blob, inner, mats.kernel(2 * SIGN_MARGIN + 1))
            Core.bitwise_not(inner, inner)
            Core.bitwise_and(ring, inner, ring)
            Core.bitwise_and(ring, mats.own(runs.submat(area)), ring)
            signs[i] = Imgproc.connectedComponents(ring, mats.mat(), 8) - 1 < SIGN_LINES
        }
        if (signs.none { it }) return
        val ids = IntArray(routes.rows() * routes.cols()).also { labels.get(0, 0, it) }
        val bytes = ByteArray(ids.size) { if (signs[ids[it]]) -1 else 0 }
        val sign = mats.own(Mat(routes.rows(), routes.cols(), CvType.CV_8U))
        sign.put(0, 0, bytes)
        Imgproc.dilate(sign, sign, mats.kernel(SIGN_MARGIN))
        Core.bitwise_not(sign, sign)
        Core.bitwise_and(clean, sign, clean)
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
            if (a != b || points.size >= 10) paths += CorridorPath(a, b, points)
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

    private fun prune(stars: List<PixelPoint>, spurLength: Double) {
        while (true) {
            val degree = degree(paths)
            val spur = paths.indexOfFirst {
                val fromEnd = degree[it.from] == 1
                if (fromEnd == (degree[it.to] == 1)) return@indexOfFirst false
                // Star paint matches the route colour, so a stub into a star is not a corridor.
                val end = nodes.getValue(if (fromEnd) it.from else it.to)
                val atStar = stars.any { star -> star.distanceTo(end) <= 40.0 }
                it.length < if (atStar) params.markerSpurLength else spurLength
            }
            if (spur < 0) return
            paths.removeAt(spur)
        }
    }

    /**
     * The junctions merged into one never spread over more than the radius, so merging can't run
     * down a chain of short edges and pull a junction far from its corridors.
     */
    private fun merge(mergeRadius: Double) {
        val members = nodes.mapValues { mutableListOf(it.value) }.toMutableMap()
        fun spread(group: List<PixelPoint>) = group.maxOf { p -> group.maxOf { it.distanceTo(p) } }
        while (true) {
            val index = paths.indexOfFirst {
                it.from != it.to && it.length < mergeRadius &&
                    spread(members.getValue(it.from) + members.getValue(it.to)) <= mergeRadius
            }
            if (index < 0) break
            val removed = paths.removeAt(index)
            val a = removed.from
            val b = removed.to
            members.getValue(a) += members.getValue(b)
            val u = nodes.getValue(a)
            val v = nodes.getValue(b)
            nodes[a] = PixelPoint((u.x + v.x) / 2, (u.y + v.y) / 2)
            nodes.remove(b)
            paths.replaceAll {
                CorridorPath(
                    if (it.from == b) a else it.from,
                    if (it.to == b) a else it.to,
                    it.points,
                )
            }
        }
        paths.removeAll { it.from == it.to && it.length <= 4 * mergeRadius }
    }

    private fun contract() {
        while (true) {
            val joint = degree(paths).entries.firstNotNullOfOrNull { (id, degree) ->
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
            paths += CorridorPath(a.other(id), b.other(id), points)
            nodes.remove(id)
        }
    }

    private fun refine(mats: Mats) {
        for ((id, degree) in degree(paths)) {
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

/**
 * Polyline from node to node that keeps to the traced line. A moved junction joins the line where
 * it passes closest, so a straight corridor bends at its end and never tilts.
 */
internal fun edgePolyline(
    start: PixelPoint,
    end: PixelPoint,
    points: List<PixelPoint>,
    tolerance: Double,
    mats: Mats,
): List<PixelPoint> {
    val fromStart = joinedAt(points, start)
    val line = joinedAt(fromStart.asReversed(), end).asReversed()
    if (line.size < 2) return listOf(start, end)
    val input = mats.own(MatOfPoint2f())
    val simple = mats.own(MatOfPoint2f())
    input.fromList(line.map { Point(it.x, it.y) })
    Imgproc.approxPolyDP(input, simple, tolerance, false)
    val out = simple.toList().map { PixelPoint(it.x, it.y) }.toMutableList()
    if (out.size < 2) return listOf(start, end)
    out[0] = start
    out[out.lastIndex] = end
    return out
}

private fun joinedAt(line: List<PixelPoint>, node: PixelPoint): List<PixelPoint> {
    val reach = 2 * line[0].distanceTo(node)
    var travelled = 0.0
    var nearest = 0
    var best = line[0].distanceTo(node)
    for (i in 1 until line.size) {
        travelled += line[i - 1].distanceTo(line[i])
        if (travelled > reach) break
        val distance = line[i].distanceTo(node)
        if (distance < best) {
            nearest = i
            best = distance
        }
    }
    return listOf(node) + line.subList(nearest, line.size)
}
