package edu.wisc.maplens

import edu.wisc.maplens.mapparse.endpointNear
import edu.wisc.maplens.mapparse.nextNodeId
import edu.wisc.maplens.mapparse.split
import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.edge
import edu.wisc.maplens.model.edgePoints
import edu.wisc.maplens.model.nearestEdge
import edu.wisc.maplens.model.otherEnd
import edu.wisc.maplens.routing.normalizeLabel

/**
 * Corrections the "Check the map" screen can make. Each returns a new graph and leaves the old
 * one alone, which is what makes undo a plain list. A refused edit throws
 * [IllegalArgumentException] with a message for the user.
 */

// The same radii the parser uses, so a corrected graph follows the same rules as a parsed one.
private const val START_REUSE_PX = 2.0
private const val DOOR_REUSE_PX = 8.0

internal fun FloorGraph.moveNode(id: String, to: PixelPoint): FloorGraph {
    val node = nodes.getValue(id)
    return copy(
        nodes = nodes + (id to node.copy(position = to)),
        edges = edges.map { edge ->
            if (edge.from != id && edge.to != id) return@map edge
            val points = edgePoints(edge).toMutableList()
            if (edge.from == id) points[0] = to
            if (edge.to == id) points[points.lastIndex] = to
            edge.copy(polyline = points, lengthPx = FloorGraphJson.polylineLength(points))
        },
    )
}

/** On a corridor within [snapPx] it joins that corridor. Elsewhere it stands alone until linked. */
internal fun FloorGraph.addPoint(at: PixelPoint, snapPx: Double): Pair<FloorGraph, String> {
    val hit = nearestEdge(at)?.takeIf { it.distancePx <= snapPx }
    if (hit == null) {
        val node = Node(nextNodeId("edP"), at, NodeType.DEAD_END)
        return copy(nodes = nodes + (node.id to node)) to node.id
    }
    val edge = edge(hit.edgeId)
    endpointNear(edge, hit.point, START_REUSE_PX)?.let { return this to it.id }
    val node = Node(nextNodeId("edJ"), hit.point, NodeType.JUNCTION)
    return split(edge, hit, node) to node.id
}

/**
 * Joins two points with a straight corridor. A deliberate edit may link any two points, so
 * crossing another corridor is left for the save check to report.
 */
internal fun FloorGraph.connect(a: String, b: String): FloorGraph {
    require(a != b) { "Pick a different point to link to." }
    require(edges.none { (it.from == a && it.to == b) || (it.from == b && it.to == a) }) {
        "Those two points are already linked."
    }
    val points = listOf(nodes.getValue(a).position, nodes.getValue(b).position)
    val length = FloorGraphJson.polylineLength(points)
    require(length > 0.0) { "Those two points are in the same spot." }
    val id = generateSequence(1) { it + 1 }.map { "edE$it" }
        .first { name -> edges.none { it.id == name } }
    return copy(edges = edges + Edge(id, a, b, points, length)).withEndTypesFixed(a, b)
}

/**
 * Removes a point. Mid-corridor, the two sides rejoin so deleting one door does not cut the
 * corridor in two. Its labels go with it, and deleting START leaves START unset.
 */
internal fun FloorGraph.deleteNode(id: String): FloorGraph {
    val cleared = copy(
        landmarks = landmarks.filterNot { it.nodeId == id },
        startNodeId = if (id == startNodeId) "" else startNodeId,
    )
    cleared.dissolve(id)?.let { return it }
    val touching = cleared.edges.filter { it.from == id || it.to == id }
    return cleared.copy(
        nodes = cleared.nodes - id,
        edges = cleared.edges - touching.toSet(),
    ).withEndTypesFixed(*touching.map { it.otherEnd(id) }.toTypedArray())
}

internal fun FloorGraph.deleteEdge(id: String): FloorGraph {
    val edge = edge(id)
    return copy(edges = edges - edge).withEndTypesFixed(edge.from, edge.to)
}

/**
 * Places START on the corridor nearest [at]. Only ever called from a deliberate user action.
 * It may replace a plain junction or corridor end, as the parser does, but never a room or exit.
 */
internal fun FloorGraph.setStart(at: PixelPoint): Pair<FloorGraph, String> {
    val hit = requireNotNull(nearestEdge(at)) { "Add a corridor before setting START." }
    val edge = edge(hit.edgeId)
    val old = nodes[startNodeId]?.takeIf { it.type == NodeType.START }
    val endpoint = endpointNear(edge, hit.point, START_REUSE_PX)
    if (endpoint != null && endpoint.id == old?.id) return this to endpoint.id
    if (endpoint != null) {
        val plain = endpoint.type == NodeType.JUNCTION || endpoint.type == NodeType.DEAD_END
        require(plain && landmarks.none { it.nodeId == endpoint.id }) {
            "That spot is a door or exit. Pick a spot beside it."
        }
    }
    val node = endpoint?.copy(type = NodeType.START)
        ?: Node(nextNodeId("edS"), hit.point, NodeType.START)
    val placed = if (endpoint == null) {
        split(edge, hit, node)
    } else {
        copy(nodes = nodes + (node.id to node))
    }
    return placed.copy(startNodeId = node.id).retired(old?.id) to node.id
}

/** The old START stops being START. A point that only existed to hold it goes away. */
private fun FloorGraph.retired(id: String?): FloorGraph {
    val node = id?.let(nodes::get) ?: return this
    if (landmarks.none { it.nodeId == id }) dissolve(id)?.let { return it }
    return copy(nodes = nodes + (id to node.copy(type = NodeType.JUNCTION))).withEndTypesFixed(id)
}

/**
 * Adds a door for [label] on the corridor nearest [at]. It reuses a nearby unlabelled corridor
 * point, but a door never lands on START, an exit or another room's door.
 */
internal fun FloorGraph.placeDoor(
    label: String,
    at: PixelPoint,
    snapPx: Double,
): Pair<FloorGraph, String> {
    val clean = cleanLabel(label)
    val hit = requireNotNull(nearestEdge(at)?.takeIf { it.distancePx <= snapPx }) {
        "Move the crosshair onto a corridor."
    }
    val edge = edge(hit.edgeId)
    val near = endpointNear(edge, hit.point, DOOR_REUSE_PX) { node ->
        landmarks.none { it.nodeId == node.id } && node.type in REUSABLE_FOR_DOOR
    }
    val door: Node
    val placed = if (near != null) {
        // A junction where three corridors meet stays one. A bend or corridor end becomes the door.
        val branches = edges.count { it.from == near.id || it.to == near.id }
        door = if (branches <= 2) near.copy(type = NodeType.DOOR) else near
        copy(nodes = nodes + (door.id to door))
    } else {
        require(endpointNear(edge, hit.point, START_REUSE_PX) == null) {
            "That spot is taken. Pick a spot beside it."
        }
        door = Node(nextNodeId("edD"), hit.point, NodeType.DOOR)
        split(edge, hit, door)
    }
    return placed.addLabel(door.id, clean) to door.id
}

private val REUSABLE_FOR_DOOR = setOf(NodeType.JUNCTION, NodeType.DEAD_END, NodeType.DOOR)

/** Adds a label to a point, or replaces [old] on it. Typed labels count as fully certain. */
internal fun FloorGraph.setLabel(nodeId: String, old: String?, new: String): FloorGraph {
    val clean = cleanLabel(new)
    if (clean == old) return this
    return (if (old == null) this else removeLabel(nodeId, old)).addLabel(nodeId, clean)
}

internal fun FloorGraph.removeLabel(nodeId: String, label: String): FloorGraph =
    copy(landmarks = landmarks.filterNot { it.nodeId == nodeId && it.label == label })

private fun FloorGraph.addLabel(nodeId: String, label: String): FloorGraph {
    if (landmarks.any { it.nodeId == nodeId && it.label == label }) return this
    return copy(landmarks = landmarks + Landmark(label, nodeId, 1f))
}

private fun cleanLabel(label: String): String =
    normalizeLabel(label).also { require(it.isNotEmpty()) { "Type the room number." } }

/** Replaces a degree-two point with one corridor, or returns null if it isn't one. */
private fun FloorGraph.dissolve(id: String): FloorGraph? {
    val touching = edges.filter { it.from == id || it.to == id }
    if (touching.size != 2) return null
    val (first, second) = touching
    val a = first.otherEnd(id)
    val b = second.otherEnd(id)
    if (a == b || a == id || b == id) return null
    val into = edgePoints(first).let { if (first.to == id) it else it.reversed() }
    val outOf = edgePoints(second).let { if (second.from == id) it else it.reversed() }
    val joined = Edge(
        first.id,
        a,
        b,
        into + outOf.drop(1),
        first.lengthPx + second.lengthPx,
    )
    return copy(
        nodes = nodes - id,
        edges = edges.filterNot { it.id == second.id }
            .map { if (it.id == first.id) joined else it },
    )
}

/** Junctions and corridor ends follow how many corridors meet there. Other types keep theirs. */
private fun FloorGraph.withEndTypesFixed(vararg ids: String): FloorGraph {
    val fixed = ids.mapNotNull { id ->
        val node = nodes[id] ?: return@mapNotNull null
        if (node.type != NodeType.JUNCTION && node.type != NodeType.DEAD_END) {
            return@mapNotNull null
        }
        val degree = edges.count { it.from == id || it.to == id }
        id to node.copy(type = if (degree >= 2) NodeType.JUNCTION else NodeType.DEAD_END)
    }
    return copy(nodes = nodes + fixed)
}

internal data class Crossing(val edgeA: String, val edgeB: String, val point: PixelPoint)

/** Corridors that properly cross. Sharing an end or merely touching does not count. */
internal fun FloorGraph.crossings(): List<Crossing> {
    val lines = edges.map { it to edgePoints(it) }
    return buildList {
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                val at = firstCrossing(lines[i].second, lines[j].second) ?: continue
                add(Crossing(lines[i].first.id, lines[j].first.id, at))
            }
        }
    }
}

private fun firstCrossing(a: List<PixelPoint>, b: List<PixelPoint>): PixelPoint? {
    for ((p, q) in a.zipWithNext()) {
        for ((r, s) in b.zipWithNext()) {
            segmentCrossing(p, q, r, s)?.let { return it }
        }
    }
    return null
}

private fun segmentCrossing(
    p: PixelPoint,
    q: PixelPoint,
    r: PixelPoint,
    s: PixelPoint,
): PixelPoint? {
    fun side(a: PixelPoint, b: PixelPoint, c: PixelPoint) =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    val d1 = side(p, q, r)
    val d2 = side(p, q, s)
    val d3 = side(r, s, p)
    val d4 = side(r, s, q)
    if (d1 * d2 >= 0.0 || d3 * d4 >= 0.0) return null
    val t = d3 / (d3 - d4)
    return PixelPoint(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y))
}

/** Adds the junction a crossing is missing, as one tap from the problem list. */
internal fun FloorGraph.joinAt(crossing: Crossing): Pair<FloorGraph, String> {
    val node = Node(nextNodeId("edJ"), crossing.point, NodeType.JUNCTION)
    fun FloorGraph.cut(id: String): FloorGraph {
        val edge = edge(id)
        val hit = checkNotNull(copy(edges = listOf(edge)).nearestEdge(crossing.point))
        return split(edge, hit, node)
    }
    return cut(crossing.edgeA).cut(crossing.edgeB) to node.id
}

/** What a tap on the map landed on. Points always win over the corridor under them. */
internal sealed interface Pick {
    /** Closest first. Several means a finger can't separate them at this zoom. */
    data class Points(val ids: List<String>) : Pick
    data class Corridor(val edgeId: String) : Pick
    data object Nothing : Pick
}

internal fun FloorGraph.pickAt(tap: PixelPoint, slopPx: Double): Pick {
    val close = nodes.values
        .map { it.id to it.position.distanceTo(tap) }
        .filter { it.second <= slopPx }
        .sortedBy { it.second }
    val second = close.getOrNull(1)?.second
    if (second != null && close[0].second <= slopPx / 3 && second >= 3 * close[0].second) {
        return Pick.Points(listOf(close[0].first))
    }
    if (close.isNotEmpty()) return Pick.Points(close.map { it.first })
    val hit = nearestEdge(tap)?.takeIf { it.distancePx <= slopPx } ?: return Pick.Nothing
    return Pick.Corridor(hit.edgeId)
}
