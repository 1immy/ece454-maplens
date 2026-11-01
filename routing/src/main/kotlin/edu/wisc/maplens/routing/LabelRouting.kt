package edu.wisc.maplens.routing

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Route

/*
 * Routing to a room by its printed label (#8).
 *
 * A room can have several entrances: each is a DOOR node with the same landmark label
 * (e.g. 3546 on the EH3 elevator poster). Routing to a label tries every one of those doors
 * and returns the shortest route, so the user is sent to the nearest entrance.
 */

/** Room labels match the printed sign: upper-case, no spaces or punctuation except '-'. */
fun normalizeLabel(label: String): String =
    label.trim().uppercase().filter { it.isLetterOrDigit() || it == '-' }

/** Room numbers like 3650 or 3539A; excludes markers such as OFFMAP and TO-EXIT. */
private val ROOM_NUMBER = Regex("""\d{3,4}[A-Z]?""")

/** Every node carrying [label] (after [normalizeLabel]), in graph order, without duplicates. */
fun FloorGraph.nodesForLabel(label: String): List<String> {
    val wanted = normalizeLabel(label)
    if (wanted.isEmpty()) return emptyList()
    return landmarks
        .filter { normalizeLabel(it.label) == wanted && it.nodeId in nodes }
        .map { it.nodeId }
        .distinct()
}

/** Sorted, distinct room numbers a user can pick as a destination. */
fun FloorGraph.roomDestinations(): List<String> =
    landmarks.map { normalizeLabel(it.label) }
        .filter { ROOM_NUMBER.matches(it) && nodesForLabel(it).isNotEmpty() }
        .distinct()
        .sorted()

/**
 * Shortest route from [fromNodeId] to the nearest node labelled [label], or null when the label
 * is unknown or none of its doors can be reached. Ties go to the lower node id, so the result
 * is deterministic. Starting at one of the label's doors gives a zero-length route (arrival).
 */
fun Router.routeToLabel(graph: FloorGraph, fromNodeId: String, label: String): Route? =
    graph.nodesForLabel(label)
        .mapNotNull { door -> route(graph, fromNodeId, door) }
        .minWithOrNull(compareBy<Route> { it.lengthPx }.thenBy { it.nodeIds.last() })
