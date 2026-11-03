package edu.wisc.maplens.live

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.corridorsAt
import edu.wisc.maplens.model.edge
import edu.wisc.maplens.model.otherEnd
import kotlin.math.PI
import kotlin.math.roundToInt

/** One answer to "which way are you facing?" at START. Tapping it sets θ0 (A2). */
data class FacingOption(
    val edgeId: String,
    /** Map bearing of the corridor (image convention, see CorridorGeometry). */
    val bearingRad: Double,
    /** Screen arrow for the bearing; the map is drawn unrotated, so it matches the map. */
    val arrow: String,
    /** What lies that way, e.g. "toward the elevator" or "past room 3418". */
    val description: String,
)

private val ARROWS = listOf("→", "↘", "↓", "↙", "←", "↖", "↑", "↗")

/** Arrow glyph for a map bearing, in 45° sectors (0 = right, π/2 = down). */
fun arrowFor(bearingRad: Double): String {
    val sector = (bearingRad / (PI / 4)).roundToInt()
    return ARROWS[((sector % 8) + 8) % 8]
}

/** Every corridor leaving [nodeId], described by the first room or feature along it. */
fun FloorGraph.facingOptions(nodeId: String): List<FacingOption> =
    corridorsAt(nodeId).map { option ->
        FacingOption(
            edgeId = option.edgeId,
            bearingRad = option.bearingRad,
            arrow = arrowFor(option.bearingRad),
            description = describeToward(nodeId, option.edgeId),
        )
    }

/**
 * Follows the corridor from [fromNodeId] along [edgeId] through plain doors until something
 * a person can see: the first labelled room, or a junction, elevator, stairs, exit or dead end.
 */
private fun FloorGraph.describeToward(fromNodeId: String, edgeId: String): String {
    val adjacency = adjacency()
    var previous = fromNodeId
    var current = edge(edgeId).otherEnd(fromNodeId)
    repeat(nodes.size) {
        val node = nodes.getValue(current)
        val label = landmarks.firstOrNull { it.nodeId == current }?.label
        when (node.type) {
            NodeType.ELEVATOR -> return "toward the elevator"
            NodeType.STAIRS -> return "toward the stairs"
            NodeType.EXIT -> return "toward an exit"
            NodeType.JUNCTION -> return "to the next junction"
            else -> Unit
        }
        if (label != null && label.first().isDigit()) return "past room $label"
        val next = adjacency[current].orEmpty().map { it.second }.filter { it != previous }
        if (next.size != 1) return "along the corridor"
        previous = current
        current = next.single()
    }
    return "along the corridor"
}
