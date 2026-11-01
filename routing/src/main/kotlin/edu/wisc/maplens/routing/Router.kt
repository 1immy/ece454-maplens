package edu.wisc.maplens.routing

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Route

/**
 * Shortest paths on a [FloorGraph], weighted by edge `lengthPx`.
 * To route to a room by its label, use [routeToLabel].
 */
interface Router {
    /** Shortest route between two nodes, or null if unreachable or either id is unknown. */
    fun route(graph: FloorGraph, fromNodeId: String, toNodeId: String): Route?
}
