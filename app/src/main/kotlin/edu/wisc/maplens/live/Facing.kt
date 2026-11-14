package edu.wisc.maplens.live

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.bearing
import edu.wisc.maplens.model.bearingDifference
import edu.wisc.maplens.model.corridorsAt
import edu.wisc.maplens.model.edge
import edu.wisc.maplens.model.edgePoints
import edu.wisc.maplens.model.otherEnd
import edu.wisc.maplens.model.wrapBearing
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One answer to "which way are you facing?" at START. Tapping it sets θ0 (A2). */
data class FacingOption(
    /** The corridor this way, or the nearest one when the user faces a wall or the poster. */
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

/**
 * The four ways a person can face at [nodeId], 90° apart, as in a real hallway: along the
 * corridor either way, toward the map poster and with their back to it.
 *
 * The bearings are the map's own corridor axes ([corridorAxisRad]), not the first stretch of
 * each traced edge: a traced corridor can leave START a little crooked, and its bearing would
 * then set θ0 off by that much for the whole walk. A direction with a corridor (within
 * [FACING_MATCH_RAD]) is described by what lies along it; the others by the poster, which
 * is drawn facing the reader, so "up" on the map is the way someone reading it faces.
 */
fun FloorGraph.facingOptions(nodeId: String): List<FacingOption> {
    val corridors = corridorsAt(nodeId)
    if (corridors.isEmpty()) return emptyList()
    val axis = corridorAxisRad()
    val options = (0 until 4).map { quarter ->
        val bearing = wrapBearing(axis + quarter * PI / 2)
        val match = corridors
            .filter { bearingDifference(it.bearingRad, bearing) <= FACING_MATCH_RAD }
            .minByOrNull { bearingDifference(it.bearingRad, bearing) }
        val nearest = match ?: corridors.minBy { bearingDifference(it.bearingRad, bearing) }
        val description = when {
            match != null -> describeToward(nodeId, match.edgeId)
            bearingDifference(bearing, -PI / 2) <= PI / 4 -> "facing the map poster"
            bearingDifference(bearing, PI / 2) <= PI / 4 -> "with your back to the map poster"
            else -> "facing the wall"
        }
        (match != null) to FacingOption(nearest.edgeId, bearing, arrowFor(bearing), description)
    }
    // Corridors first (the way most people stand to start walking), then facing the poster.
    return options.sortedWith(
        compareBy({ !it.first }, { bearingDifference(it.second.bearingRad, -PI / 2) }),
    ).map { it.second }
}

/**
 * The map's corridor direction, in (-π/4, π/4]: the length-weighted mean of every segment's
 * bearing folded into a quarter turn. 0 for a poster straightened square to the image.
 */
fun FloorGraph.corridorAxisRad(): Double {
    var sx = 0.0
    var sy = 0.0
    for (edge in edges) {
        for ((a, b) in edgePoints(edge).zipWithNext()) {
            val length = a.distanceTo(b)
            val folded = 4 * bearing(a, b)
            sx += length * cos(folded)
            sy += length * sin(folded)
        }
    }
    if (sx == 0.0 && sy == 0.0) return 0.0
    return atan2(sy, sx) / 4
}

/** A corridor this close to one of the four directions is "that way". */
private val FACING_MATCH_RAD = Math.toRadians(35.0)

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
