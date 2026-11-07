package edu.wisc.maplens

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.routing.normalizeLabel
import edu.wisc.maplens.routing.roomDestinations

/** A number read from the poster. Plain values, so editing it tests without Android. */
internal data class RoomDraft(
    val label: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val entranceNodeId: String?,
    val confidence: Float,
    val possible: Boolean = false,
    /** Someone looked and accepted it as it stands, such as two rooms sharing a real door. */
    val checked: Boolean = false,
)

/** The map being checked: the graph, and the readings that may not be in it yet. */
internal data class MapDraft(val graph: FloorGraph, val rooms: List<RoomDraft>) {
    fun checkOf(index: Int): RoomCheck {
        val room = rooms[index]
        val hasDoor = room.entranceNodeId in graph.nodes
        if (room.checked && hasDoor) return RoomCheck.CLEAR
        val own = hasDoor && graph.landmarks.count { it.nodeId == room.entranceNodeId } == 1
        return roomCheck(room.confidence, room.possible, own)
    }

    /** Where a room falls in the checking order: doubtful numbers first, then by number. */
    fun checkOrder(index: Int): String = "${checkOf(index).ordinal}:${rooms[index].label}"

    fun roomsToCheck(): List<Int> = rooms.indices
        .filter { checkOf(it) != RoomCheck.CLEAR }
        .sortedBy(::checkOrder)

    /** The next flagged room after a place in the order, wrapping round to the first. */
    fun nextToCheck(after: String? = null): Int? {
        val flagged = roomsToCheck()
        return flagged.firstOrNull { after != null && checkOrder(it) > after }
            ?: flagged.firstOrNull()
    }

    /** A graph edit. A room whose door was deleted goes back to needing one. */
    fun withGraph(next: FloorGraph): MapDraft = copy(
        graph = next,
        rooms = rooms.map { room ->
            if (room.entranceNodeId in next.nodes) room else room.copy(entranceNodeId = null)
        },
    )

    /** The readings placed on a point, as indexes into [rooms]. */
    fun roomsAt(nodeId: String): List<Int> =
        rooms.indices.filter { rooms[it].entranceNodeId == nodeId }

    /** The user looked and the reading is right. A possible room still needs its door placed. */
    fun acceptRoom(index: Int): MapDraft {
        val room = rooms[index]
        val door = room.entranceNodeId
        val marked = if (door == null) {
            graph
        } else {
            graph.removeLabel(door, room.label).setLabel(door, null, room.label)
        }
        return copy(
            graph = marked,
            rooms = rooms.replaced(
                index,
                room.copy(confidence = 1f, possible = false, checked = true),
            ),
        )
    }

    fun renameRoom(index: Int, label: String): MapDraft {
        val room = rooms[index]
        val door = room.entranceNodeId
        val renamed = if (door == null) graph else graph.setLabel(door, room.label, label)
        val typed = normalizeLabel(label)
        require(typed.isNotEmpty()) { "Type the room number." }
        return copy(
            graph = renamed,
            rooms = rooms.replaced(
                index,
                room.copy(label = typed, confidence = 1f, possible = false),
            ),
        )
    }

    fun placeRoomDoor(index: Int, at: PixelPoint, snapPx: Double): MapDraft {
        val room = rooms[index]
        val without = room.entranceNodeId?.let { graph.removeLabel(it, room.label) } ?: graph
        val (next, door) = without.placeDoor(room.label, at, snapPx)
        return copy(
            graph = next,
            rooms = rooms.replaced(
                index,
                room.copy(entranceNodeId = door, possible = false, checked = true),
            ),
        )
    }

    /** The reading was not a room. Its label goes, unless another reading shares that door. */
    fun removeRoom(index: Int): MapDraft {
        val room = rooms[index]
        val others = rooms.filterIndexed { i, _ -> i != index }
        val door = room.entranceNodeId
        val keep = door == null ||
            others.any { it.entranceNodeId == door && it.label == room.label }
        return copy(
            graph = if (keep) graph else graph.removeLabel(door!!, room.label),
            rooms = others
        )
    }

    /** A label typed on a point keeps the room list in step with the graph. */
    fun labelPoint(nodeId: String, old: String?, new: String): MapDraft {
        val next = graph.setLabel(nodeId, old, new)
        val typed = normalizeLabel(new)
        return copy(
            graph = next,
            rooms = rooms.map { room ->
                if (old != null && room.entranceNodeId == nodeId && room.label == old) {
                    room.copy(label = typed, confidence = 1f, possible = false)
                } else {
                    room
                }
            },
        )
    }

    private fun List<RoomDraft>.replaced(index: Int, room: RoomDraft) =
        toMutableList().also { it[index] = room }
}

/**
 * Room list for a graph with no parse behind it, such as a traced fixture, with a small box at
 * each door so the list and the map still work.
 */
internal fun FloorGraph.roomsFromLabels(): List<RoomDraft> {
    val rooms = roomDestinations().toSet()
    return landmarks.filter { normalizeLabel(it.label) in rooms }.mapNotNull { mark ->
        val at = nodes[mark.nodeId]?.position ?: return@mapNotNull null
        RoomDraft(
            mark.label,
            at.x.toInt() - FIXTURE_BOX_PX,
            at.y.toInt() - FIXTURE_BOX_PX,
            at.x.toInt() + FIXTURE_BOX_PX,
            at.y.toInt() + FIXTURE_BOX_PX,
            mark.nodeId,
            mark.ocrConfidence,
        )
    }
}

private const val FIXTURE_BOX_PX = 16

/** Every edit is one step back. Graphs are immutable, so a step is only a reference. */
internal class EditHistory<T>(initial: T) {
    private val earlier = ArrayDeque<T>()
    var current: T = initial
        private set
    val canUndo get() = earlier.isNotEmpty()

    fun push(next: T) {
        if (next == current) return
        earlier.addLast(current)
        current = next
    }

    fun undo() {
        current = earlier.removeLastOrNull() ?: return
    }
}
