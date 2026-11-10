package edu.wisc.maplens.nav

import edu.wisc.maplens.guidance.RerouteParams
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.nearestEdge
import edu.wisc.maplens.routing.Reroute
import edu.wisc.maplens.routing.Router
import edu.wisc.maplens.routing.rerouteFrom
import edu.wisc.maplens.routing.rerouteToLabel

/**
 * A new route to [place] from where [pose] puts the walker, preferring the way they face
 * (#80): to the nearest door of a room, or the nearest of a feature's nodes. Null when there
 * is no way from here.
 */
fun Router.rerouteTo(
    graph: FloorGraph,
    place: Place,
    pose: PoseEstimate,
    params: RerouteParams,
): Reroute? {
    val on = graph.nearestEdge(pose.position) ?: return null
    val penalty = params.px(params.uTurnPenaltyMeters, pose.stepLengthPx)
    val label = place.label
    return if (label != null) {
        rerouteToLabel(graph, on.edgeId, on.offsetPx, pose.headingRad, label, penalty)
    } else {
        rerouteFrom(graph, on.edgeId, on.offsetPx, pose.headingRad, place.nodeIds, penalty)
    }
}
