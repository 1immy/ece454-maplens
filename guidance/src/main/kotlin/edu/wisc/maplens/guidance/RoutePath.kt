package edu.wisc.maplens.guidance

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.Route
import edu.wisc.maplens.model.bearing
import edu.wisc.maplens.model.edge
import edu.wisc.maplens.model.edgePoints
import kotlin.math.hypot

/**
 * A [Route] flattened into one polyline, walked from its first node to its last.
 * Distances are measured along the drawn polyline, in pixels of the straightened map.
 */
class RoutePath(graph: FloorGraph, val route: Route) {
    /** Polyline vertices in walking order, without repeated points. */
    val points: List<PixelPoint>

    /** Distance along the path to each vertex; `distances[0] == 0`. */
    val distances: List<Double>

    /** Route node at each vertex where one sits (vertex index -> node id). */
    val nodeAt: Map<Int, String>

    /** Edge id of each segment `points[i] -> points[i + 1]`. */
    private val segmentEdges: List<String>

    init {
        require(route.nodeIds.isNotEmpty()) { "empty route" }
        require(route.edgeIds.size == route.nodeIds.size - 1) { "route edges and nodes differ" }
        val first = graph.nodes[route.nodeIds.first()]
            ?: throw IllegalArgumentException("unknown node ${route.nodeIds.first()}")
        val pts = mutableListOf(first.position)
        val edges = mutableListOf<String>()
        val nodes = mutableMapOf(0 to first.id)
        route.edgeIds.forEachIndexed { i, edgeId ->
            val edge = graph.edge(edgeId)
            val forward = edge.from == route.nodeIds[i]
            require(forward || edge.to == route.nodeIds[i]) { "edge $edgeId is not at node $i" }
            val drawn = graph.edgePoints(edge).let { if (forward) it else it.reversed() }
            for (p in drawn.drop(1)) {
                if (p != pts.last()) {
                    pts += p
                    edges += edgeId
                }
            }
            nodes[pts.lastIndex] = route.nodeIds[i + 1]
        }
        points = pts
        segmentEdges = edges
        nodeAt = nodes
        distances = pts.indices.runningFold(0.0) { acc, i ->
            if (i == 0) acc else acc + pts[i - 1].distanceTo(pts[i])
        }.drop(1)
    }

    val lengthPx: Double get() = distances.last()

    /** Segment holding distance [s] (clamped to the path). */
    private fun segmentAt(s: Double): Int {
        if (segmentEdges.isEmpty()) return -1
        val i = distances.indexOfFirst { it > s }
        return if (i <= 0) (if (i == 0) 0 else segmentEdges.lastIndex) else i - 1
    }

    fun pointAt(s: Double): PixelPoint {
        val i = segmentAt(s)
        if (i < 0) return points.first()
        val a = points[i]
        val b = points[i + 1]
        val length = distances[i + 1] - distances[i]
        val t = ((s - distances[i]) / length).coerceIn(0.0, 1.0)
        return PixelPoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
    }

    /** Walking bearing at [s] (image convention, see CorridorGeometry); 0 for a one-node route. */
    fun bearingAt(s: Double): Double {
        val i = segmentAt(s)
        return if (i < 0) 0.0 else bearing(points[i], points[i + 1])
    }

    /** Edge under distance [s], or null for a one-node route. */
    fun edgeAt(s: Double): String? = segmentAt(s).takeIf { it >= 0 }?.let { segmentEdges[it] }

    /** Distance along the path of the point on it closest to [p]; ties go to the earlier one. */
    fun project(p: PixelPoint): Double {
        var bestS = 0.0
        var bestD = Double.POSITIVE_INFINITY
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val length = distances[i + 1] - distances[i]
            val dot = (p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)
            val t = (dot / (length * length)).coerceIn(0.0, 1.0)
            val d = hypot(p.x - (a.x + t * (b.x - a.x)), p.y - (a.y + t * (b.y - a.y)))
            if (d < bestD - 1e-9) {
                bestD = d
                bestS = distances[i] + t * length
            }
        }
        return bestS
    }
}
