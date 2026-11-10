package edu.wisc.maplens.routing

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Route
import edu.wisc.maplens.model.bearingDifference
import edu.wisc.maplens.model.bearingOnEdge
import edu.wisc.maplens.model.edge

/**
 * A new route from where the walker is now (#80).
 *
 * [route] starts at the far end of the corridor the walker is on and runs through them, so
 * projecting their position onto it gives their progress, and the stretch behind them already
 * counts as walked. [turnAround] is true when it leaves the corridor the way they came, so the
 * first instruction is "Turn around". [costPx] is the distance from the walker to the target,
 * plus any U-turn penalty.
 */
data class Reroute(val route: Route, val turnAround: Boolean, val costPx: Double)

/**
 * Shortest way from a point [offsetPx] along edge [edgeId] (from `edge.from`) to the nearest of
 * [targets], for a walker facing [headingRad].
 *
 * Both ends of the corridor are tried. Leaving by the end behind the walker costs an extra
 * [uTurnPenaltyPx], so a way ahead is kept unless turning round is clearly shorter, the way a
 * maps app avoids sending a driver into needless U-turns. A way ahead that only loops back
 * through this same corridor is never offered: turning round is always shorter. Returns null
 * when no target can be reached.
 */
fun Router.rerouteFrom(
    graph: FloorGraph,
    edgeId: String,
    offsetPx: Double,
    headingRad: Double,
    targets: List<String>,
    uTurnPenaltyPx: Double,
): Reroute? {
    val edge = graph.edge(edgeId)
    val offset = offsetPx.coerceIn(0.0, edge.lengthPx)
    val towardTo = graph.bearingOnEdge(edgeId, offset, towardTo = true)
    val facingTo = bearingDifference(headingRad, towardTo) <= Math.PI / 2
    var best: Reroute? = null
    for ((exit, entry) in listOf(edge.to to edge.from, edge.from to edge.to)) {
        val toExit = if (exit == edge.to) edge.lengthPx - offset else offset
        val behind = (exit == edge.to) != facingTo
        for (target in targets.distinct()) {
            val rest = route(graph, exit, target) ?: continue
            // A way out that comes straight back along this corridor is a U-turn in disguise.
            if (rest.edgeIds.firstOrNull() == edge.id) continue
            val cost = toExit + rest.lengthPx + if (behind) uTurnPenaltyPx else 0.0
            val current = best
            if (current == null || cost < current.costPx - 1e-9) {
                val route = Route(
                    nodeIds = listOf(entry) + rest.nodeIds,
                    edgeIds = listOf(edge.id) + rest.edgeIds,
                    lengthPx = edge.lengthPx + rest.lengthPx,
                )
                best = Reroute(route, turnAround = behind, costPx = cost)
            }
        }
    }
    return best
}

/** [rerouteFrom] to the nearest door of room [label]. */
fun Router.rerouteToLabel(
    graph: FloorGraph,
    edgeId: String,
    offsetPx: Double,
    headingRad: Double,
    label: String,
    uTurnPenaltyPx: Double,
): Reroute? = rerouteFrom(
    graph,
    edgeId,
    offsetPx,
    headingRad,
    graph.nodesForLabel(label),
    uTurnPenaltyPx,
)
