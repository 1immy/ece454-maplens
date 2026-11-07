package edu.wisc.maplens.mapparse

import android.graphics.Rect
import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.EdgePosition
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.edge
import edu.wisc.maplens.model.nearestEdge
import kotlin.math.ceil

internal data class RoomAttachments(val graph: FloorGraph, val rooms: List<RoomPreview>)

internal fun FloorGraph.attachRooms(
    rooms: List<PosterReading>,
    walls: ByteArray,
    width: Int,
    warnings: MutableList<String>,
): RoomAttachments {
    var graph = this
    val previews = mutableListOf<RoomPreview>()
    val samples = edges.flatMap { edge ->
        edge.polyline.zipWithNext().flatMap { (a, b) ->
            val steps = ceil(a.distanceTo(b) / 3.0).toInt().coerceAtLeast(1)
            (0..steps).map { interpolate(a, b, it.toDouble() / steps) }
        }
    }
    val missed = mutableSetOf<String>()
    val detours = mutableSetOf<String>()
    for (room in rooms) {
        checkCancellation()
        val connector = corridorPath(room.center, samples, walls, width)
        val hit = connector?.let { graph.nearestEdge(it.target) }
        if (connector == null || hit == null) {
            previews += RoomPreview(room.text, Rect(room.box), null, room.confidence)
            missed += room.text
            continue
        }
        val direct = room.center.distanceTo(hit.point)
        if (connector.length > 1.5 * direct + 20.0) {
            previews += RoomPreview(room.text, Rect(room.box), null, room.confidence)
            detours += room.text
            continue
        }
        val edge = graph.edge(hit.edgeId)
        var node = graph.endpointNear(edge, hit.point, 8.0)
        if (node == null) {
            node = Node(graph.nextNodeId("ocrD"), hit.point, NodeType.DOOR)
            graph = graph.split(edge, hit, node)
        } else if (node.type == NodeType.DEAD_END) {
            node = node.copy(type = NodeType.DOOR)
            graph = graph.copy(nodes = graph.nodes + (node.id to node))
        }
        previews += RoomPreview(room.text, Rect(room.box), node.id, room.confidence)
        if (graph.landmarks.none { it.nodeId == node.id && it.label == room.text }) {
            graph = graph.copy(
                landmarks = graph.landmarks +
                    Landmark(room.text, node.id, room.confidence)
            )
        }
    }
    if (missed.isNotEmpty()) {
        warnings += "No wall-clear entrance found for ${missed.joinToString()}. Check these rooms."
    }
    if (detours.isNotEmpty()) {
        warnings += "Entrance path takes a long detour for ${detours.joinToString()}. " +
            "Check the doorway and any missing corridors."
    }
    for (group in graph.landmarks.groupBy { it.nodeId }.values.filter { it.size > 1 }) {
        warnings += "Rooms ${group.joinToString { it.label }} share a proposed entrance. Check it."
    }
    return RoomAttachments(graph, previews)
}

internal fun resolveStart(
    graph: FloorGraph,
    stars: List<PixelPoint>,
    warnings: MutableList<String>,
): FloorGraph {
    if (stars.size == 1) return attachStart(graph, stars.single(), warnings)
    warnings += if (stars.isEmpty()) {
        "Gold star not found. Set START in review or retake with less glare."
    } else {
        "Several gold stars match. Choose START in review."
    }
    return graph
}

private fun attachStart(
    graph: FloorGraph,
    marker: PixelPoint,
    warnings: MutableList<String>,
): FloorGraph {
    val hit = graph.nearestEdge(marker)
    if (hit == null || hit.distancePx > 40.0) {
        warnings += "The gold star is too far from the extracted corridors. Set START in review."
        return graph
    }
    if (graph.copy(startNodeId = graph.nodes.keys.first()).validate().isNotEmpty()) {
        warnings += "Corridors are disconnected. Connect them before setting START."
        return graph
    }
    val edge = graph.edge(hit.edgeId)
    val endpoint = graph.endpointNear(edge, hit.point, 2.0) {
        it.position.distanceTo(marker) <= 100.0
    }
    if (endpoint != null) {
        val eligible = endpoint.type in setOf(NodeType.JUNCTION, NodeType.DEAD_END, NodeType.START)
        if (!eligible || graph.landmarks.any { it.nodeId == endpoint.id }) {
            warnings += "The star overlaps a room or landmark. Set START in review."
            return graph
        }
    }
    val node = endpoint?.copy(type = NodeType.START)
        ?: Node(graph.nextNodeId("s"), hit.point, NodeType.START)
    val result = if (endpoint == null) {
        graph.split(edge, hit, node)
    } else {
        graph.copy(nodes = graph.nodes + (node.id to node))
    }
    return result.copy(startNodeId = node.id)
}

fun FloorGraph.endpointNear(
    edge: Edge,
    point: PixelPoint,
    radius: Double,
    accept: (Node) -> Boolean = { true },
): Node? = listOf(nodes.getValue(edge.from), nodes.getValue(edge.to)).firstOrNull {
    it.position.distanceTo(point) <= radius && accept(it)
}

fun FloorGraph.nextNodeId(prefix: String): String =
    generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in nodes }

/** Inserts [node] into [edge] at [hit]. Node ids don't change, so room landmarks stay put. */
fun FloorGraph.split(edge: Edge, hit: EdgePosition, node: Node): FloorGraph {
    val points = edge.polyline
    var distance = hit.offsetPx
    val drawnLength = pathLength(points)
    distance *= drawnLength / edge.lengthPx
    var segment = 0
    while (segment < points.lastIndex - 1) {
        val length = points[segment].distanceTo(points[segment + 1])
        if (distance <= length) break
        distance -= length
        segment++
    }
    val left = (points.take(segment + 1) + node.position).withoutRepeatedPoints()
    val right = (listOf(node.position) + points.drop(segment + 1)).withoutRepeatedPoints()
    val newEdgeId = generateSequence(1) { it + 1 }.map { "split$it" }
        .first { id -> edges.none { it.id == id } }
    val before = edge.copy(to = node.id, polyline = left, lengthPx = pathLength(left))
    val after = Edge(newEdgeId, node.id, edge.to, right, pathLength(right))
    return copy(
        nodes = nodes + (node.id to node),
        edges = edges.flatMap { if (it.id == edge.id) listOf(before, after) else listOf(it) },
    )
}

private fun List<PixelPoint>.withoutRepeatedPoints(): List<PixelPoint> =
    filterIndexed { i, point -> i == 0 || point != this[i - 1] }
