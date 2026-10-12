package edu.wisc.maplens.routing

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.Route
import edu.wisc.maplens.model.otherEnd
import java.util.PriorityQueue

/**
 * A* over pixel lengths. The straight-line heuristic is admissible because every
 * edge length is the length of its polyline, which is never shorter than the chord.
 */
class AStarRouter : Router {
    override fun route(graph: FloorGraph, fromNodeId: String, toNodeId: String): Route? {
        val goal = graph.nodes[toNodeId] ?: return null
        val start = graph.nodes[fromNodeId] ?: return null
        val adjacency = graph.adjacency()

        val costSoFar = hashMapOf(fromNodeId to 0.0)
        val cameBy = HashMap<String, Edge>()
        val closed = HashSet<String>()
        val open = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        open.add(fromNodeId to heuristic(start, goal))

        while (open.isNotEmpty()) {
            val current = open.poll().first
            if (current == toNodeId) {
                return reconstruct(cameBy, fromNodeId, toNodeId, costSoFar.getValue(current))
            }
            if (!closed.add(current)) continue
            val currentCost = costSoFar.getValue(current)
            for ((edge, neighbour) in adjacency[current].orEmpty()) {
                if (neighbour in closed) continue
                val candidate = currentCost + edge.lengthPx
                if (candidate < (costSoFar[neighbour] ?: Double.POSITIVE_INFINITY)) {
                    costSoFar[neighbour] = candidate
                    cameBy[neighbour] = edge
                    val estimate = candidate + heuristic(graph.nodes.getValue(neighbour), goal)
                    open.add(neighbour to estimate)
                }
            }
        }
        return null
    }

    private fun heuristic(from: Node, goal: Node): Double = from.position.distanceTo(goal.position)

    private fun reconstruct(
        cameBy: Map<String, Edge>,
        fromNodeId: String,
        toNodeId: String,
        lengthPx: Double,
    ): Route {
        val nodeIds = mutableListOf(toNodeId)
        val edgeIds = mutableListOf<String>()
        var node = toNodeId
        while (node != fromNodeId) {
            val edge = cameBy.getValue(node)
            edgeIds += edge.id
            node = edge.otherEnd(node)
            nodeIds += node
        }
        return Route(nodeIds.reversed(), edgeIds.reversed(), lengthPx)
    }
}
