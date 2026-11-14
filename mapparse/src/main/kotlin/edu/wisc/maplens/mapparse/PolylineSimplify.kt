package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import kotlin.math.hypot

/** Douglas–Peucker: drops points within [tolerance] px of the line kept; the two ends stay. */
fun simplifyPolyline(points: List<PixelPoint>, tolerance: Double): List<PixelPoint> {
    if (points.size <= 2) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.lastIndex)
    while (stack.isNotEmpty()) {
        val (from, to) = stack.removeLast()
        var worst = -1
        var worstDistance = tolerance
        for (i in from + 1 until to) {
            val d = distanceToSegment(points[i], points[from], points[to])
            if (d > worstDistance) {
                worst = i
                worstDistance = d
            }
        }
        if (worst >= 0) {
            keep[worst] = true
            stack.addLast(from to worst)
            stack.addLast(worst to to)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

/**
 * The graph with every edge's points simplified to [tolerancePx]; nodes stay where they are.
 * Edges that don't change are returned as they are.
 */
fun FloorGraph.simplifiedEdges(tolerancePx: Double): FloorGraph =
    copy(
        edges = edges.map { edge ->
            if (edge.polyline.size <= 2) return@map edge
            val line = simplifyPolyline(edge.polyline, tolerancePx)
            if (line.size == edge.polyline.size) {
                edge
            } else {
                edge.copy(
                    polyline = line,
                    lengthPx = line.zipWithNext { a, b -> a.distanceTo(b) }.sum(),
                )
            }
        },
    )

private fun distanceToSegment(p: PixelPoint, a: PixelPoint, b: PixelPoint): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length2 = dx * dx + dy * dy
    if (length2 == 0.0) return p.distanceTo(a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}
