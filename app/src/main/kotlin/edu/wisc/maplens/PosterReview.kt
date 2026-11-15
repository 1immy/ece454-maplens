package edu.wisc.maplens

import edu.wisc.maplens.mapparse.DoorIds
import edu.wisc.maplens.mapparse.ParseResult
import edu.wisc.maplens.mapparse.RoomPreview
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.routing.nodesForLabel
import edu.wisc.maplens.routing.normalizeLabel
import edu.wisc.maplens.routing.roomDestinations

/**
 * One thing wrong with a map. [blocksSave] problems stop it being verified, the rest are shown in
 * the save confirmation. [at] is where to look, and [crossing] can be fixed with one tap.
 */
internal data class MapProblem(
    val message: String,
    val at: PixelPoint? = null,
    val blocksSave: Boolean = true,
    val crossing: Crossing? = null,
)

/** The one gate for a verified map, from the rules in project-AI-context section 7 and ADR 0008. */
internal fun mapProblems(graph: FloorGraph): List<MapProblem> = buildList {
    val start = graph.nodes[graph.startNodeId]?.takeIf { it.type == NodeType.START }
    if (start == null) add(MapProblem("Set START before verifying the map."))

    fun position(id: String) = graph.nodes[id]?.position
    val structural = graph.validate().filterNot {
        it.startsWith("start node") || it.startsWith("nodes not connected") ||
            it.endsWith("non-positive length")
    }
    structural.forEach { add(MapProblem(it)) }
    graph.edges.filter { it.lengthPx <= 0.0 }.forEach {
        add(MapProblem("Two linked points are in the same spot. Move one.", position(it.from)))
    }

    val origin = start?.id ?: graph.nodes.keys.firstOrNull()
    val reachable = origin?.let { graph.reachableFrom(it) }.orEmpty()
    val cutOff = graph.nodes.keys - reachable
    if (cutOff.isNotEmpty()) {
        val where = if (start == null) "from the rest of the map" else "from START"
        add(
            MapProblem(
                "${cutOff.size} points are cut off $where. Link them.",
                position(cutOff.first())
            )
        )
    }
    if (start != null) {
        val unreachable = graph.landmarks.groupBy { it.label }.filterValues { doors ->
            doors.none { it.nodeId in reachable }
        }
        if (unreachable.isNotEmpty()) {
            add(
                MapProblem(
                    "Rooms unreachable from START: ${unreachable.keys.sorted().joinToString()}.",
                    unreachable.values.first().firstNotNullOfOrNull { position(it.nodeId) },
                ),
            )
        }
    }

    graph.landmarks.filter { normalizeLabel(it.label).isEmpty() }.forEach {
        add(MapProblem("A room has an empty number.", position(it.nodeId)))
    }
    if (structural.isEmpty()) {
        graph.crossings().forEach {
            add(MapProblem("Corridors cross with no junction.", it.point, crossing = it))
        }
    }

    // A label on several doors is a room with several entrances, which is normal.
    for (room in graph.roomDestinations()) {
        for (id in graph.nodesForLabel(room)) {
            val type = graph.nodes.getValue(id).type
            if (type != NodeType.DOOR) {
                add(MapProblem("Room $room is not on a door.", position(id), blocksSave = false))
            }
        }
    }
    for ((id, marks) in graph.landmarks.groupBy { it.nodeId }.filterValues { it.size > 1 }) {
        val names = marks.joinToString { it.label }
        add(MapProblem("Rooms $names share one door.", position(id), blocksSave = false))
    }
}

internal fun reviewProblems(graph: FloorGraph): List<String> =
    mapProblems(graph).map { it.message }

private fun FloorGraph.reachableFrom(origin: String): Set<String> {
    val seen = mutableSetOf(origin)
    val links = adjacency()
    val pending = ArrayDeque(listOf(origin))
    while (pending.isNotEmpty()) {
        for ((_, next) in links[pending.removeLast()].orEmpty()) {
            if (seen.add(next)) pending.addLast(next)
        }
    }
    return seen
}

// Below this on the test images: all 3 wrong low-resolution reads, and 6% of right ones.
private const val CLEAR_READ = 0.97f

/** In the order rooms are listed: doubtful numbers first. */
internal enum class RoomCheck { POSSIBLE, NOT_SURE, ENTRANCE, CLEAR }

internal data class ReviewRoom(val preview: RoomPreview, val check: RoomCheck) {
    val isDoubtful get() = check == RoomCheck.NOT_SURE || check == RoomCheck.POSSIBLE
}

internal fun roomCheck(
    confidence: Float,
    possible: Boolean,
    hasOwnEntrance: Boolean,
): RoomCheck = when {
    possible -> RoomCheck.POSSIBLE
    confidence < CLEAR_READ -> RoomCheck.NOT_SURE
    !hasOwnEntrance -> RoomCheck.ENTRANCE
    else -> RoomCheck.CLEAR
}

internal fun reviewRooms(result: ParseResult): List<ReviewRoom> {
    val graph = result.graph
    val shared = graph.landmarks.groupingBy { it.nodeId }.eachCount()
    return result.rooms.map { room ->
        // A door the fallback search placed is a guess: the review asks for a look.
        val own = room.entranceNodeId in graph.nodes && shared[room.entranceNodeId] == 1 &&
            room.entranceNodeId?.let(DoorIds::needsReview) != true
        ReviewRoom(room, roomCheck(room.confidence, room.possible, own))
    }.sortedWith(compareBy({ it.check }, { it.preview.label }))
}
