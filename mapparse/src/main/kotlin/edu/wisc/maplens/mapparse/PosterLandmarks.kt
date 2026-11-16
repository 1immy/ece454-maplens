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
    ink: ByteArray? = null,
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
    // Each room's door is found from the walls (watershedDoors): where the room's floor meets
    // reachable corridor floor through a door-sized gap, else an approximate end beside the
    // room. The search only reads the poster; the graph then changes one room at a time.
    val timer = StageTimer()
    // Seeded watershed over open floor (see watershedDoors): rooms, corridor and outside grow
    // from their seeds and meet at the narrowest places, which are the doorways.
    val findings = watershedDoors(walls, width, rooms, samples, ink)
    timer.mark("doors")
    // Rooms that can't be closed off: the old cheapest path out, flagged for review.
    val needPath = rooms.indices.filter { findings[it] == null }
    val paths = needPath.parallelMap { corridorPath(rooms[it].center, samples, walls, width) }
    val connectors = arrayOfNulls<CorridorConnector>(rooms.size)
    for ((k, i) in needPath.withIndex()) connectors[i] = paths[k]
    timer.mark("fallback paths for ${needPath.size} of ${rooms.size} rooms")
    for ((index, room) in rooms.withIndex()) {
        checkCancellation()
        val finding = findings[index]
        android.util.Log.d(
            "MapLensDoors",
            "${room.text}: ${finding?.kind ?: DoorKind.REVIEW} " +
                (
                    finding?.let {
                        "door ${it.doorway} out ${it.outside} -> ${it.corridor}; ${it.reason}"
                    } ?: "fallback"
                    ),
        )
        if (finding != null) {
            graph = graph.withFoundDoor(room, finding, previews, walls, width)
            continue
        }
        val connector = connectors[index]
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
        graph = graph.withDoor(room, hit, previews, DoorKind.REVIEW)
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

/**
 * The door [finding] found for [room]: on the corridor where the way out reaches it, or, when
 * that is more than [DIRECT_LINK_PX] away (an untraced stretch of corridor), at the walker's
 * spot outside the doorway with a link edge over open floor to the traced corridor.
 */
private fun FloorGraph.withFoundDoor(
    room: PosterReading,
    finding: DoorFinding,
    previews: MutableList<RoomPreview>,
    walls: ByteArray,
    width: Int,
): FloorGraph {
    // On the corridor centre line straight across from the opening, when nothing is in the
    // way: the tracing convention, and where a walker heads for the door.
    // The walk out shows whether the corridor is straight across: short and nearly straight.
    val across = nearestEdge(finding.outside)
    val walked = pathLength(finding.link)
    if (across != null && across.distancePx <= ACROSS_PX &&
        walked <= STRAIGHT_SHARE * across.distancePx + STRAIGHT_SLACK_PX
    ) {
        return withDoor(room, across, previews, finding.kind)
    }
    val hit = nearestEdge(finding.corridor) ?: return this
    if (pathLength(finding.link) <= DIRECT_LINK_PX) {
        return withDoor(room, hit, previews, finding.kind)
    }
    var graph = this
    val edge = graph.edge(hit.edgeId)
    val joint = graph.endpointNear(edge, hit.point, 3.0)
        ?: Node(graph.nextNodeId("dj"), hit.point, NodeType.JUNCTION).also {
            graph = graph.split(edge, hit, it)
        }
    val door = Node(graph.nextNodeId(doorIdPrefix(finding.kind)), finding.outside, NodeType.DOOR)
    var start = joint
    var line = listOf(joint.position) + finding.link.reversed().drop(1).dropLast(1) + door.position
    // Rooms reached through one opening walk the same way there. Their links branch where they
    // part; near-identical traced lines would otherwise wobble across each other. Each fork can
    // lead along another room's link, so look again from there. The line shrinks every time.
    while (true) {
        val (shared, last) = graph.sharedLink(start, line) ?: break
        val hit = checkNotNull(graph.copy(edges = listOf(shared)).nearestEdge(line[last]))
        // The whole way out runs along that link, so the door is on it. A fork here would be a
        // zero-length link, which blocks Save.
        if (last == line.lastIndex) return graph.withDoor(room, hit, previews, finding.kind)
        start = graph.endpointNear(shared, hit.point, 3.0)
            ?: Node(graph.nextNodeId("fj"), hit.point, NodeType.JUNCTION).also {
                graph = graph.split(shared, hit, it)
            }
        line = listOf(start.position) + line.drop(last + 1)
    }
    val linkId = generateSequence(1) { it + 1 }.map { "door$it" }
        .first { id -> graph.edges.none { it.id == id } }
    graph = graph.copy(
        nodes = graph.nodes + (door.id to door),
        edges = graph.edges + Edge(linkId, start.id, door.id, line, pathLength(line)),
    )
    return graph.labelled(room, door.id, previews)
}

/**
 * A DOOR for [room] at corridor point [hit]. A corridor end within 8 px is reused, unless it is
 * another room's door: neighbours on one corridor keep their own doors.
 */
private fun FloorGraph.withDoor(
    room: PosterReading,
    hit: EdgePosition,
    previews: MutableList<RoomPreview>,
    kind: DoorKind = DoorKind.VERIFIED,
): FloorGraph {
    var graph = this
    val edge = graph.edge(hit.edgeId)
    val othersDoor = { node: Node ->
        graph.landmarks.any { it.nodeId == node.id && it.label != room.text }
    }
    // Both ends are tried: rejecting a nearby neighbour's door must not skip the end the hit
    // sits on, or the new door lands on top of it with a zero-length edge.
    var node = graph.endpointNear(edge, hit.point, 8.0) {
        !othersDoor(it) || it.position.distanceTo(hit.point) < 1.5
    }
    if (node == null) {
        node = Node(graph.nextNodeId(doorIdPrefix(kind)), hit.point, NodeType.DOOR)
        graph = graph.split(edge, hit, node)
    } else if (node.type == NodeType.DEAD_END) {
        node = node.copy(type = NodeType.DOOR)
        graph = graph.copy(nodes = graph.nodes + (node.id to node))
    }
    return graph.labelled(room, node.id, previews)
}

/** Names node [nodeId] as [room]'s entrance in the graph and the review list. */
private fun FloorGraph.labelled(
    room: PosterReading,
    nodeId: String,
    previews: MutableList<RoomPreview>,
): FloorGraph {
    var graph = this
    val node = graph.nodes.getValue(nodeId)
    if (previews.none { it.label == room.text && it.entranceNodeId != null }) {
        previews.removeAll { it.label == room.text }
        previews += RoomPreview(room.text, Rect(room.box), node.id, room.confidence)
    }
    if (graph.landmarks.none { it.nodeId == node.id && it.label == room.text }) {
        val label = Landmark(room.text, node.id, room.confidence)
        graph = graph.copy(landmarks = graph.landmarks + label)
    }
    return graph
}

/**
 * The edge leaving [joint] that [line] (also from [joint]) runs along furthest, at least
 * [SHARED_MIN_PX], with the index of [line]'s last point still on it. Null when there is none.
 */
internal fun FloorGraph.sharedLink(joint: Node, line: List<PixelPoint>): Pair<Edge, Int>? =
    edges.filter { joint.id == it.from || joint.id == it.to }.map { edge ->
        var last = 0
        while (last < line.lastIndex &&
            distanceToLine(line[last + 1], edge.polyline) <= SHARED_PX
        ) {
            last++
        }
        edge to last
    }.filter { (_, last) -> last > 0 && pathLength(line.take(last + 1)) >= SHARED_MIN_PX }
        .maxByOrNull { (_, last) -> last }

private fun distanceToLine(point: PixelPoint, line: List<PixelPoint>): Double =
    line.zipWithNext().minOfOrNull { (a, b) ->
        val dx = b.x - a.x
        val dy = b.y - a.y
        val squared = dx * dx + dy * dy
        val t = if (squared == 0.0) {
            0.0
        } else {
            (((point.x - a.x) * dx + (point.y - a.y) * dy) / squared).coerceIn(0.0, 1.0)
        }
        point.distanceTo(PixelPoint(a.x + t * dx, a.y + t * dy))
    } ?: point.distanceTo(line.first())

/** Door links this close together walk the same way. */
private const val SHARED_PX = 3.0

/** Shorter shared stretches are left as they are: they can't wobble across each other. */
private const val SHARED_MIN_PX = 10.0

/** A door this close to the traced corridor sits on it; further, it gets a link edge. */
private const val DIRECT_LINK_PX = 25.0

/** Furthest from the doorway to the corridor centre line for a door placed straight across. */
private const val ACROSS_PX = 80.0

/** The walk out may be this much longer than the straight line and still count as across. */
private const val STRAIGHT_SHARE = 1.6
private const val STRAIGHT_SLACK_PX = 15.0

/** No wall pixel on the straight line from [a] to [b]. */
private fun lineClear(a: PixelPoint, b: PixelPoint, walls: ByteArray, width: Int): Boolean {
    val steps = kotlin.math.ceil(a.distanceTo(b)).toInt().coerceAtLeast(1)
    val height = walls.size / width
    for (i in 0..steps) {
        val x = (a.x + (b.x - a.x) * i / steps).toInt()
        val y = (a.y + (b.y - a.y) * i / steps).toInt()
        if (x !in 0 until width || y !in 0 until height) return false
        if (walls[y * width + x] != 0.toByte()) return false
    }
    return true
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
    if (hit == null || hit.distancePx > STAR_MAX_GAP_PX) {
        warnings += "The gold star is too far from the extracted corridors. Set START in review."
        return graph
    }
    if (graph.copy(startNodeId = graph.nodes.keys.first()).validate().isNotEmpty()) {
        warnings += "Corridors are disconnected. Connect them before setting START."
        return graph
    }
    // Most stars sit on the route paint; some sit beside it, by a stair door (EH3 stair 3542:
    // 94 px off). The walker stands at the poster, so START goes exactly at the star, joined
    // to the corridor by a short link, rather than slid along to the nearest corridor point.
    if (hit.distancePx > STAR_ON_LINE_PX) return startBeside(graph, marker, hit)
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

/** The star counts as on the corridor within this; beyond it START is flagged for review. */
private const val STAR_ON_LINE_PX = 40.0

/** Past this the star is not by any traced corridor and START is left to the user. */
private const val STAR_MAX_GAP_PX = 120.0

/** START at [marker], linked straight to the corridor point [hit] (split there if needed). */
private fun startBeside(graph: FloorGraph, marker: PixelPoint, hit: EdgePosition): FloorGraph {
    val edge = graph.edge(hit.edgeId)
    val near = graph.endpointNear(edge, hit.point, NODE_SNAP_PX)
    val (joined, joint) = if (near != null) {
        graph to near
    } else {
        val node = Node(graph.nextNodeId("j"), hit.point, NodeType.JUNCTION)
        graph.split(edge, hit, node) to node
    }
    val start = Node(joined.nextNodeId("s"), marker, NodeType.START)
    val linkId = generateSequence(1) { it + 1 }.map { "start$it" }
        .first { id -> joined.edges.none { it.id == id } }
    val link = Edge(linkId, joint.id, start.id, listOf(joint.position, marker), hit.distancePx)
    return joined.copy(
        nodes = joined.nodes + (start.id to start),
        edges = joined.edges + link,
        startNodeId = start.id,
    )
}

/** A corridor end this close to where the star's link lands takes the link itself. */
private const val NODE_SNAP_PX = 12.0

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
