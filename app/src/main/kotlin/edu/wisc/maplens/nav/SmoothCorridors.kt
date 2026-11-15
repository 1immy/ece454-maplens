package edu.wisc.maplens.nav

import edu.wisc.maplens.crossings
import edu.wisc.maplens.mapparse.simplifiedEdges
import edu.wisc.maplens.model.FloorGraph

/**
 * The graph with each corridor's traced line smoothed: wiggles under [tolerancePx] are taken
 * out (Douglas–Peucker), so routes, turn cues and the tracker follow the corridor's straight
 * line, not the skeleton's jitter (EH3 scans, Nov 13). Every node, door and START stays exactly
 * where it is; only the points between them change. Applied when a map is opened, so maps
 * saved earlier get it too.
 */
fun FloorGraph.smoothedCorridors(tolerancePx: Double = SMOOTH_TOLERANCE_PX): FloorGraph {
    var smooth = simplifiedEdges(tolerancePx)
    // A straighter line can cut across a short link beside it (Nov 13 scan: a corridor
    // straightened across a door link by 3446), which the map didn't have and the route
    // planner refuses. Such a corridor keeps its traced line.
    val traced = edges.associateBy { it.id }
    repeat(edges.size) {
        val crossed = smooth.crossings().flatMap { listOf(it.edgeA, it.edgeB) }
            .filter { id -> smooth.edges.first { it.id == id } != traced[id] }
            .toSet()
        if (crossed.isEmpty()) return smooth
        smooth = smooth.copy(
            edges = smooth.edges.map { if (it.id in crossed) traced.getValue(it.id) else it },
        )
    }
    return smooth
}

/** About a fifth of a corridor's width on a 2000 px poster: real bends are far larger. */
const val SMOOTH_TOLERANCE_PX = 10.0
