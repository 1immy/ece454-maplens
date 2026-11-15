package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.nearestEdge
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Tolerances for [straightenedAround]. Pixels of the 2000 px straightened poster. */
internal data class StartStraightenParams(
    /** The skeleton bends toward the gold star within about this far of it. */
    val nearPx: Double = 60.0,
    /** The corridor's own line is read from this far out along it. */
    val sampleToPx: Double = 240.0,
    /** Samples must lie this close to one line (RMS) to count as a straight corridor. */
    val maxResidualPx: Double = 6.0,
    /** A point never moves further than this onto the line. */
    val maxMovePx: Double = 35.0,
    /** The star must sit this close to a traced corridor to be on it. */
    val maxStarGapPx: Double = 60.0,
)

/**
 * Straightens the corridor where the gold "You Are Here" [star] sits, before rooms and START
 * are attached. The star is painted on the route line and the traced skeleton bends into it,
 * so doors attached there and START itself sat off the corridor, and START's first stretches
 * ran crooked (EH3 scans, Nov 12–13: 31°/149° and ~30° off the corridor). Those stretches set
 * the "which way are you facing" bearings, so θ0 came out wrong for the walk.
 *
 * The corridor's line is fitted to the trace [StartStraightenParams.nearPx] to sampleToPx
 * either side of the star, following plain degree-2 points; every trace point and degree-2
 * node within nearPx of the star moves onto that line. Junctions, ends and every other part
 * of the trace stay as traced. If the star is at a corner or junction, or the corridor isn't
 * straight there, the graph is returned unchanged.
 */
internal fun FloorGraph.straightenedAround(
    star: PixelPoint,
    params: StartStraightenParams = StartStraightenParams(),
): FloorGraph {
    val hit = nearestEdge(star) ?: return this
    if (hit.distancePx > params.maxStarGapPx) return this
    val edge = edges.first { it.id == hit.edgeId }
    val line = edgePoints(edge.id)
    // The trace either side of the star: walk out from its nearest stretch both ways.
    val split = line.indices.drop(1).firstOrNull { i ->
        cumulative(line, i) >= cumulative(line, line.lastIndex) * hit.offsetPx / edge.lengthPx
    } ?: line.lastIndex
    val back =
        samples(listOf(hit.point) + line.take(split).reversed(), edge.from, edge.id, star, params)
    val ahead = samples(listOf(hit.point) + line.drop(split), edge.to, edge.id, star, params)
    if (back.isEmpty() || ahead.isEmpty()) return this
    val fit = fitLine(back + ahead) ?: return this
    if (fit.residual > params.maxResidualPx) return this
    // Opposite sides of the star along the line, or this is a corner, not a corridor.
    val mid = fit.along(star)
    if ((back.map { fit.along(it) - mid }.average() > 0) ==
        (ahead.map { fit.along(it) - mid }.average() > 0)
    ) {
        return this
    }
    fun moved(p: PixelPoint): PixelPoint {
        if (p.distanceTo(star) > params.nearPx) return p
        val q = fit.project(p)
        return if (q.distanceTo(p) <= params.maxMovePx) q else p
    }
    val degree = nodes.keys.associateWith { id -> edges.count { it.from == id || it.to == id } }
    val newNodes = nodes.mapValues { (id, node) ->
        if (degree[id] == 2) node.copy(position = moved(node.position)) else node
    }
    val newEdges = edges.map { e ->
        val points = edgePoints(e.id)
        val inner = points.drop(1).dropLast(1).map(::moved)
        val polyline = listOf(newNodes.getValue(e.from).position) + inner +
            newNodes.getValue(e.to).position
        if (polyline == points) e else e.copy(polyline = polyline, lengthPx = pathLength(polyline))
    }
    return copy(nodes = newNodes, edges = newEdges)
}

/**
 * Takes the skeleton's bumps out of the corridors before rooms and features attach. Thinning
 * bends the centre line toward a side opening or a stair lobby for a few tens of pixels and
 * back (EH3 by 3425: 16 px up toward the stair exit over 80 px, then down again; by n28 9 px
 * toward the east branch), and exits and doors attached there sat on the bump, so a route
 * along a straight corridor zig-zagged. A bump under [tolerancePx] is about a third of a
 * corridor's width; real corners are far larger.
 */
internal fun FloorGraph.withoutBumps(tolerancePx: Double = BUMP_PX): FloorGraph =
    simplifiedEdges(tolerancePx)

internal const val BUMP_PX = 18.0

private fun FloorGraph.edgePoints(edgeId: String): List<PixelPoint> {
    val edge = edges.first { it.id == edgeId }
    return edge.polyline.ifEmpty {
        listOf(nodes.getValue(edge.from).position, nodes.getValue(edge.to).position)
    }
}

private fun cumulative(points: List<PixelPoint>, upTo: Int): Double =
    points.take(upTo + 1).zipWithNext { a, b -> a.distanceTo(b) }.sum()

/**
 * Points every 4 px along [start] (trace leaving the star) and on past [node] through
 * degree-2 nodes, between nearPx and sampleToPx from [star].
 */
private fun FloorGraph.samples(
    start: List<PixelPoint>,
    node: String,
    fromEdge: String,
    star: PixelPoint,
    params: StartStraightenParams,
): List<PixelPoint> {
    val out = mutableListOf<PixelPoint>()
    var points = start
    var at = node
    var edgeId = fromEdge
    val seen = mutableSetOf(fromEdge)
    while (true) {
        for ((a, b) in points.zipWithNext()) {
            val steps = (a.distanceTo(b) / 4.0).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val p = PixelPoint(a.x + (b.x - a.x) * i / steps, a.y + (b.y - a.y) * i / steps)
                val d = p.distanceTo(star)
                if (d > params.sampleToPx) return out
                if (d > params.nearPx) out += p
            }
        }
        // On through the node along whichever corridor carries straight on, if one does.
        val heading = points.takeLast(2).let { (a, b) -> atan2(b.y - a.y, b.x - a.x) }
        val next = edges.filter { (it.from == at || it.to == at) && it.id !in seen }
            .map { candidate ->
                val line = edgePoints(candidate.id)
                candidate to (if (candidate.from == at) line else line.reversed())
            }
            .filter { (_, line) -> line.size >= 2 }
            .minByOrNull { (_, line) -> turn(heading, line[0], line[1]) }
            ?.takeIf { (_, line) -> turn(heading, line[0], line[1]) <= STRAIGHT_ON_RAD }
            ?: return out
        seen += next.first.id
        points = next.second
        val candidate = next.first
        at = if (candidate.from == at) candidate.to else candidate.from
        edgeId = candidate.id
    }
}

/** A corridor leaving a node within this of the way in carries straight on. */
private val STRAIGHT_ON_RAD = Math.toRadians(25.0)

private fun turn(heading: Double, a: PixelPoint, b: PixelPoint): Double {
    val d = atan2(b.y - a.y, b.x - a.x) - heading
    return kotlin.math.abs(atan2(sin(d), cos(d)))
}

private class Line(val centre: PixelPoint, angle: Double, val residual: Double) {
    private val ux = cos(angle)
    private val uy = sin(angle)

    fun along(p: PixelPoint) = (p.x - centre.x) * ux + (p.y - centre.y) * uy

    fun project(p: PixelPoint): PixelPoint {
        val t = along(p)
        return PixelPoint(centre.x + t * ux, centre.y + t * uy)
    }
}

/** Least-squares (principal axis) line through [points], or null with too few. */
private fun fitLine(points: List<PixelPoint>): Line? {
    if (points.size < 4) return null
    val cx = points.sumOf { it.x } / points.size
    val cy = points.sumOf { it.y } / points.size
    var sxx = 0.0
    var syy = 0.0
    var sxy = 0.0
    for (p in points) {
        val dx = p.x - cx
        val dy = p.y - cy
        sxx += dx * dx
        syy += dy * dy
        sxy += dx * dy
    }
    if (sxx + syy == 0.0) return null
    val angle = 0.5 * atan2(2 * sxy, sxx - syy)
    val nx = -sin(angle)
    val ny = cos(angle)
    val residual = sqrt(
        points.sumOf {
            val d = (it.x - cx) * nx + (it.y - cy) * ny
            d * d
        } / points.size,
    )
    return Line(PixelPoint(cx, cy), angle, residual)
}
