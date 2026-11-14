package edu.wisc.maplens.nav

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import kotlin.math.hypot

/**
 * The graph with each corridor's traced line smoothed: wiggles under [tolerancePx] are taken
 * out (Douglas–Peucker), so routes, turn cues and the tracker follow the corridor's straight
 * line, not the skeleton's jitter (EH3 scans, Nov 13). Every node, door and START stays exactly
 * where it is; only the points between them change. Applied when a map is opened, so maps
 * saved earlier get it too.
 */
fun FloorGraph.smoothedCorridors(tolerancePx: Double = SMOOTH_TOLERANCE_PX): FloorGraph =
    copy(
        edges = edges.map { edge ->
            if (edge.polyline.size <= 2) return@map edge
            val line = simplify(edge.polyline, tolerancePx)
            if (line.size == edge.polyline.size) {
                edge
            } else {
                edge.copy(
                    polyline = line,
                    lengthPx = line.zipWithNext { a, b -> a.distanceTo(b) }.sum()
                )
            }
        },
    )

/** Douglas–Peucker; the two ends always stay. */
internal fun simplify(points: List<PixelPoint>, tolerance: Double): List<PixelPoint> {
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

private fun distanceToSegment(p: PixelPoint, a: PixelPoint, b: PixelPoint): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length2 = dx * dx + dy * dy
    if (length2 == 0.0) return p.distanceTo(a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** About a fifth of a corridor's width on a 2000 px poster: real bends are far larger. */
const val SMOOTH_TOLERANCE_PX = 10.0
