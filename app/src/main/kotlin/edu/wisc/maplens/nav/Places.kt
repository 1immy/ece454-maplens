package edu.wisc.maplens.nav

import edu.wisc.maplens.mapparse.DoorIds
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.routing.nodesForLabel
import edu.wisc.maplens.routing.normalizeLabel
import edu.wisc.maplens.routing.roomDestinations

enum class PlaceKind { ROOM, EXIT, ELEVATOR, AED }

/**
 * Something a user can search for and walk to: a room (routed by its label, so the nearest of
 * its doors wins) or a single feature node such as an exit.
 */
data class Place(
    /** Stable id, e.g. "room:3418" or "exit:E23". */
    val key: String,
    val kind: PlaceKind,
    /** Room number for rooms; null for features, which route to [nodeIds] directly. */
    val label: String?,
    val nodeIds: List<String>,
    /** Where the pin goes: the room's first door, or the feature node. */
    val position: PixelPoint,
    /**
     * The parser found no clear doorway, only the corridor beside the room: arriving there
     * means "near the room, check the door sign".
     */
    val approximate: Boolean = false,
)

/** Every room with a door on the graph, then each exit, elevator and AED node. */
fun FloorGraph.places(): List<Place> {
    val rooms = roomDestinations().map { label ->
        val doors = nodesForLabel(label)
        Place(
            "room:$label",
            PlaceKind.ROOM,
            label,
            doors,
            nodes.getValue(doors.first()).position,
            approximate = doors.all(DoorIds::isApproximate),
        )
    }
    val features = nodes.values
        .mapNotNull { node ->
            val kind = when (node.type) {
                NodeType.EXIT -> PlaceKind.EXIT
                NodeType.ELEVATOR -> PlaceKind.ELEVATOR
                NodeType.AED -> PlaceKind.AED
                else -> null
            } ?: return@mapNotNull null
            Place("${kind.name.lowercase()}:${node.id}", kind, null, listOf(node.id), node.position)
        }
        .sortedBy { it.key }
    return rooms + features
}

/** The labelled room nearest to [point] in a straight line, to describe where a feature is. */
fun FloorGraph.nearestRoomLabel(point: PixelPoint): String? {
    val rooms = roomDestinations().toSet()
    return landmarks
        .filter { normalizeLabel(it.label) in rooms && it.nodeId in nodes }
        .minByOrNull { nodes.getValue(it.nodeId).position.distanceTo(point) }
        ?.let { normalizeLabel(it.label) }
}

/** One search hit; [matched] is the part of the room number that matched the query. */
data class PlaceMatch(val place: Place, val matched: IntRange?)

/**
 * Ranks [places] for a typed query, the way a map search box does: room numbers that start
 * with the query first, then ones that contain it, then features whose name starts with it.
 * Within a rank, closer places (by [walkPx], route length from START) come first.
 */
class PlaceSearch(
    private val places: List<Place>,
    private val walkPx: (Place) -> Double?,
) {
    fun search(query: String, limit: Int = 30): List<PlaceMatch> {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return emptyList()
        val ranked = places.mapNotNull { place ->
            val label = place.label
            if (label != null) {
                val at = label.indexOf(q)
                when {
                    at == 0 -> Triple(place, 0, at until at + q.length)
                    at > 0 -> Triple(place, 1, at until at + q.length)
                    else -> null
                }
            } else {
                val words = KEYWORDS.getValue(place.kind)
                if (words.any { it.startsWith(q) }) Triple(place, 2, null) else null
            }
        }
        return ranked
            .sortedWith(
                compareBy<Triple<Place, Int, IntRange?>> { it.second }
                    .thenBy { walkPx(it.first) ?: Double.MAX_VALUE }
                    .thenBy { it.first.key },
            )
            .take(limit)
            .map { PlaceMatch(it.first, it.third) }
    }

    /** Nearest place of [kind] by walking distance, e.g. the nearest exit. */
    fun nearest(kind: PlaceKind): Place? =
        places.filter { it.kind == kind && walkPx(it) != null }.minByOrNull { walkPx(it)!! }

    /**
     * Feature kinds with at least one reachable place, so a shortcut is only offered when it
     * leads somewhere. A poster scanned before exits, elevators and AEDs were detected has none.
     */
    fun available(): Set<PlaceKind> =
        places.filter { it.kind != PlaceKind.ROOM && walkPx(it) != null }.map { it.kind }.toSet()

    companion object {
        private val KEYWORDS = mapOf(
            PlaceKind.ROOM to emptyList(),
            PlaceKind.EXIT to listOf("EXIT", "EMERGENCY", "STAIRS", "OUT"),
            PlaceKind.ELEVATOR to listOf("ELEVATOR", "LIFT"),
            PlaceKind.AED to listOf("AED", "DEFIBRILLATOR", "FIRST AID"),
        )

        /** "Room 34" and "rm34" both search for "34"; feature words keep their spaces. */
        fun normalizeQuery(query: String): String {
            val upper = query.trim().uppercase()
            val stripped = upper.removePrefix("ROOM").removePrefix("RM").trim()
            return if (stripped.any { it.isDigit() }) normalizeLabel(stripped) else upper
        }
    }
}
