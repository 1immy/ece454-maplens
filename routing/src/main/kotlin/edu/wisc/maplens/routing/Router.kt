package edu.wisc.maplens.routing

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Route

interface Router {
    /** Shortest route between two nodes, or null if unreachable or either id is unknown. */
    fun route(graph: FloorGraph, fromNodeId: String, toNodeId: String): Route?
}
