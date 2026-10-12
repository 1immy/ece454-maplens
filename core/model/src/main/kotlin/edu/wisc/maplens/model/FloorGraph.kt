package edu.wisc.maplens.model

import kotlin.math.hypot

/** A point in pixel space on the rectified (straightened) map image. */
data class PixelPoint(val x: Double, val y: Double) {
    fun distanceTo(other: PixelPoint): Double = hypot(x - other.x, y - other.y)
}

enum class NodeType { JUNCTION, DOOR, START, DEAD_END, ELEVATOR, STAIRS, EXIT, AED }

data class Node(
    val id: String,
    val position: PixelPoint,
    val type: NodeType,
)

/** Undirected corridor segment. [polyline] runs from [from] to [to]. */
data class Edge(
    val id: String,
    val from: String,
    val to: String,
    val polyline: List<PixelPoint>,
    val lengthPx: Double,
)

fun Edge.otherEnd(nodeId: String): String = if (nodeId == from) to else from

/** A room label (or other sign text) attached to the door node it belongs to. */
data class Landmark(
    val label: String,
    val nodeId: String,
    val ocrConfidence: Float,
)

/**
 * Routable corridor graph for one floor, in pixel units of the rectified map.
 *
 * Producer: graph editor (Area A). Consumers: router, tracker (Areas B and C).
 * Routing works in pixels; meters are display-only.
 */
data class FloorGraph(
    val buildingId: String,
    val floor: String,
    val nodes: Map<String, Node>,
    val edges: List<Edge>,
    val landmarks: List<Landmark>,
    val startNodeId: String,
    val verified: Boolean = false,
) {
    /** Node id -> (edge, neighbour node id) pairs, both directions. */
    fun adjacency(): Map<String, List<Pair<Edge, String>>> {
        val result = HashMap<String, MutableList<Pair<Edge, String>>>()
        for (edge in edges) {
            result.getOrPut(edge.from) { mutableListOf() }.add(edge to edge.to)
            result.getOrPut(edge.to) { mutableListOf() }.add(edge to edge.from)
        }
        return result
    }

    fun landmark(label: String): Landmark? = landmarks.firstOrNull { it.label == label }

    /**
     * Structural checks the "Check the map" step must pass before a graph can be saved.
     * Returns an empty list when the graph is usable.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (startNodeId !in nodes) problems += "start node '$startNodeId' does not exist"
        for (edge in edges) {
            if (edge.from !in nodes) {
                problems += "edge ${edge.id} starts at missing node ${edge.from}"
            }
            if (edge.to !in nodes) problems += "edge ${edge.id} ends at missing node ${edge.to}"
            if (edge.lengthPx <= 0.0) problems += "edge ${edge.id} has non-positive length"
        }
        for (landmark in landmarks) {
            if (landmark.nodeId !in nodes) {
                problems += "landmark '${landmark.label}' points at missing node ${landmark.nodeId}"
            }
        }
        if (problems.isEmpty() && nodes.isNotEmpty()) {
            val unreachable = nodes.keys - reachableFrom(startNodeId)
            if (unreachable.isNotEmpty()) {
                problems += "nodes not connected to start: ${unreachable.sorted().joinToString()}"
            }
        }
        return problems
    }

    private fun reachableFrom(origin: String): Set<String> {
        val adjacency = adjacency()
        val seen = mutableSetOf(origin)
        val stack = ArrayDeque(listOf(origin))
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for ((_, neighbour) in adjacency[current].orEmpty()) {
                if (seen.add(neighbour)) stack.addLast(neighbour)
            }
        }
        return seen
    }
}
