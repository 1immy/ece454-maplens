package edu.wisc.maplens.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * Corridor geometry helpers for the tracker, router and guidance.
 *
 * Conventions (match tracking/HeadingEstimator):
 * - All geometry is in pixels of the straightened map image, origin top-left, y down.
 * - A bearing is atan2(dy, dx) on that image, in radians, wrapped to (-PI, PI]:
 *   0 = right, PI/2 = down, -PI/2 = up. Clockwise on screen. Not a compass bearing.
 * - An offset along an edge is in [0, lengthPx], measured from edge.from toward edge.to.
 *   It is mapped onto the drawn polyline by fraction of length, so offsets stay valid
 *   even if lengthPx and the polyline's drawn length differ slightly.
 */

/** A corridor leaving a node: walk along [edgeId] toward [towardNodeId], facing [bearingRad]. */
data class CorridorOption(
    val edgeId: String,
    val towardNodeId: String,
    val bearingRad: Double,
)

/** The closest point on the corridor network to some pixel position. */
data class EdgePosition(
    val edgeId: String,
    /** Distance from edge.from along the edge, in [0, lengthPx]. */
    val offsetPx: Double,
    val point: PixelPoint,
    /** Straight-line distance from the queried position to [point]. */
    val distancePx: Double,
)

/** Wraps an angle to (-PI, PI]. */
fun wrapBearing(angle: Double): Double {
    var a = angle % (2 * PI)
    if (a <= -PI) a += 2 * PI
    if (a > PI) a -= 2 * PI
    return a
}

/** Bearing of the straight line from [a] to [b] (see file conventions). */
fun bearing(a: PixelPoint, b: PixelPoint): Double = atan2(b.y - a.y, b.x - a.x)

/** Smallest absolute difference between two bearings, in [0, PI]. */
fun bearingDifference(a: Double, b: Double): Double = abs(wrapBearing(a - b))

fun FloorGraph.edge(edgeId: String): Edge =
    edges.firstOrNull { it.id == edgeId }
        ?: throw IllegalArgumentException("unknown edge '$edgeId'")

/** The edge's polyline, or the straight line between its nodes if none was stored. */
fun FloorGraph.edgePoints(edge: Edge): List<PixelPoint> {
    if (edge.polyline.size >= 2) return edge.polyline
    val a = nodes[edge.from]?.position
        ?: throw IllegalArgumentException("edge ${edge.id}: missing node ${edge.from}")
    val b = nodes[edge.to]?.position
        ?: throw IllegalArgumentException("edge ${edge.id}: missing node ${edge.to}")
    return listOf(a, b)
}

/** Segment index and the point at [offsetPx] along [edge]. */
private fun FloorGraph.locate(edge: Edge, offsetPx: Double): Pair<Int, PixelPoint> {
    val points = edgePoints(edge)
    val drawn = points.zipWithNext { a, b -> a.distanceTo(b) }
    val total = drawn.sum()
    if (total == 0.0) return 0 to points.first()
    val fraction = if (edge.lengthPx > 0) (offsetPx / edge.lengthPx).coerceIn(0.0, 1.0) else 0.0
    var remaining = fraction * total
    for ((i, length) in drawn.withIndex()) {
        if (remaining <= length || i == drawn.lastIndex) {
            val t = if (length == 0.0) 0.0 else (remaining / length).coerceIn(0.0, 1.0)
            val a = points[i]
            val b = points[i + 1]
            return i to PixelPoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
        }
        remaining -= length
    }
    return drawn.lastIndex to points.last()
}

/** Point at [offsetPx] along the edge, measured from edge.from. */
fun FloorGraph.pointOnEdge(edgeId: String, offsetPx: Double): PixelPoint =
    locate(edge(edgeId), offsetPx).second

/**
 * Corridor bearing at [offsetPx] along the edge, for someone walking from edge.from toward
 * edge.to ([towardTo] = true) or the reverse.
 */
fun FloorGraph.bearingOnEdge(edgeId: String, offsetPx: Double, towardTo: Boolean = true): Double {
    val edge = edge(edgeId)
    val points = edgePoints(edge)
    var (segment, _) = locate(edge, offsetPx)
    // Skip zero-length segments so the bearing is always defined.
    while (segment < points.lastIndex - 1 && points[segment] == points[segment + 1]) {
        segment++
    }
    val forward = bearing(points[segment], points[segment + 1])
    return if (towardTo) forward else wrapBearing(forward + PI)
}

/**
 * Every corridor leaving [nodeId], with the bearing of its first stretch.
 * Use it for the tap-to-face arrows at START and for turn cues at junctions.
 */
fun FloorGraph.corridorsAt(nodeId: String): List<CorridorOption> {
    require(nodeId in nodes) { "unknown node '$nodeId'" }
    return edges.flatMap { edge ->
        buildList {
            if (edge.from == nodeId) {
                add(CorridorOption(edge.id, edge.to, bearingOnEdge(edge.id, 0.0, towardTo = true)))
            }
            if (edge.to == nodeId) {
                val back = bearingOnEdge(edge.id, edge.lengthPx, towardTo = false)
                add(CorridorOption(edge.id, edge.from, back))
            }
        }
    }
}

/** Closest point on any corridor to [point]. Null only for a graph without edges. */
fun FloorGraph.nearestEdge(point: PixelPoint): EdgePosition? {
    var best: EdgePosition? = null
    for (edge in edges) {
        val points = edgePoints(edge)
        val drawn = points.zipWithNext { a, b -> a.distanceTo(b) }
        val total = drawn.sum()
        var travelled = 0.0
        for (i in drawn.indices) {
            val a = points[i]
            val b = points[i + 1]
            val length = drawn[i]
            val t = if (length == 0.0) {
                0.0
            } else {
                val dot = (point.x - a.x) * (b.x - a.x) + (point.y - a.y) * (b.y - a.y)
                (dot / (length * length)).coerceIn(0.0, 1.0)
            }
            val onLine = PixelPoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
            val distance = hypot(point.x - onLine.x, point.y - onLine.y)
            if (best == null || distance < best.distancePx) {
                val alongDrawn = travelled + t * length
                val offset = if (total == 0.0) 0.0 else alongDrawn / total * edge.lengthPx
                best = EdgePosition(edge.id, offset, onLine, distance)
            }
            travelled += length
        }
    }
    return best
}
