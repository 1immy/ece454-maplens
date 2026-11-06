package edu.wisc.maplens

import edu.wisc.maplens.mapparse.ParseResult
import edu.wisc.maplens.mapparse.RoomPreview
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType

/** The editor can use these checks before asking the user to confirm a graph. */
internal fun reviewProblems(graph: FloorGraph): List<String> {
    val start = graph.nodes[graph.startNodeId]?.takeIf { it.type == NodeType.START }
    val problems = graph.validate().filterNot { start == null && it.startsWith("start node") }
    if (start == null) {
        val connectivity = graph.nodes.keys.firstOrNull()?.let {
            graph.copy(startNodeId = it).validate().any { problem ->
                problem.startsWith("nodes not connected")
            }
        }
        return buildList {
            add("Set START before verifying the map.")
            addAll(problems)
            if (connectivity == true) add("Connect the separated corridors before verifying rooms.")
        }
    }

    val reachable = mutableSetOf(start.id)
    val adjacency = graph.adjacency()
    val pending = ArrayDeque(listOf(start.id))
    while (pending.isNotEmpty()) {
        for ((_, next) in adjacency[pending.removeLast()].orEmpty()) {
            if (reachable.add(next)) pending.addLast(next)
        }
    }
    val unreachable = graph.landmarks.groupBy { it.label }.filterValues { doors ->
        doors.none { it.nodeId in reachable }
    }.keys.sorted()
    if (unreachable.isEmpty()) return problems
    return problems + "Rooms unreachable from START: ${unreachable.joinToString()}."
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
        val own = room.entranceNodeId in graph.nodes && shared[room.entranceNodeId] == 1
        ReviewRoom(room, roomCheck(room.confidence, room.possible, own))
    }.sortedWith(compareBy({ it.check }, { it.preview.label }))
}
