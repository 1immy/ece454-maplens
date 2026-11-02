package edu.wisc.maplens.guidance

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.Route
import edu.wisc.maplens.model.bearing
import edu.wisc.maplens.model.wrapBearing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil

/** Turn-cue thresholds. Starting values from the report; tuned with the UI, then frozen. */
data class GuidanceParams(
    /** Direction changes smaller than this need no cue ("bends > 30°"). */
    val turnMinDeg: Double = 30.0,
    /** Up to this angle the cue is a slight turn. */
    val slightMaxDeg: Double = 60.0,
    /** From this angle the cue is a U-turn. */
    val uTurnMinDeg: Double = 150.0,
    /** Arrived once the destination is closer than this many steps. */
    val arriveWithinSteps: Double = 1.0,
)

/** A maneuver at [distancePx] along the route; [nodeId] is null for a bend inside an edge. */
data class Cue(val distancePx: Double, val maneuver: Maneuver, val nodeId: String?)

/**
 * Turns a [Route] plus the latest [PoseEstimate] into the next [Instruction].
 * Pure Kotlin so it unit-tests without a phone; the arrow, voice and vibration all read it.
 */
class RouteGuide(
    private val graph: FloorGraph,
    route: Route,
    private val params: GuidanceParams = GuidanceParams(),
) {
    val path = RoutePath(graph, route)

    /** Every maneuver along the route in order; the last one is always ARRIVE. */
    val cues: List<Cue> = buildList {
        for (i in 1 until path.points.lastIndex) {
            val into = bearing(path.points[i - 1], path.points[i])
            val out = bearing(path.points[i], path.points[i + 1])
            val maneuver = classify(wrapBearing(out - into)) ?: continue
            add(Cue(path.distances[i], maneuver, path.nodeAt[i]))
        }
        add(Cue(path.lengthPx, Maneuver.ARRIVE, route.nodeIds.last()))
    }

    private val destinationLabel: String? =
        graph.landmarks.firstOrNull { it.nodeId == route.nodeIds.last() }?.label

    /** Positive [turn] is clockwise on the y-down image, i.e. a right turn for the walker. */
    private fun classify(turn: Double): Maneuver? {
        val deg = abs(turn) * 180 / PI
        val right = turn > 0
        return when {
            deg < params.turnMinDeg -> null
            deg >= params.uTurnMinDeg -> Maneuver.U_TURN
            deg <= params.slightMaxDeg -> if (right) Maneuver.SLIGHT_RIGHT else Maneuver.SLIGHT_LEFT
            else -> if (right) Maneuver.TURN_RIGHT else Maneuver.TURN_LEFT
        }
    }

    /** How far along the route [pose] is, in pixels. */
    fun progressPx(pose: PoseEstimate): Double = path.project(pose.position)

    /** Steps left to the destination at the pose's current k. */
    fun remainingSteps(pose: PoseEstimate): Int =
        ceil((path.lengthPx - progressPx(pose)) / pose.stepLengthPx).toInt()

    fun instruction(pose: PoseEstimate): Instruction {
        val s = progressPx(pose)
        val k = pose.stepLengthPx
        if (path.lengthPx - s <= params.arriveWithinSteps * k) {
            return Instruction(Maneuver.ARRIVE, 0, arrivalDetail())
        }
        val next = cues.first { it.distancePx > s }
        val steps = ceil((next.distancePx - s) / k).toInt().coerceAtLeast(1)
        val detail = if (next.maneuver == Maneuver.ARRIVE) arrivalDetail() else where(next)
        return Instruction(next.maneuver, steps, detail)
    }

    private fun arrivalDetail() = destinationLabel?.let { "at room $it" } ?: "at your destination"

    private fun where(cue: Cue): String {
        val node = cue.nodeId?.let { graph.nodes[it] } ?: return "where the corridor bends"
        val label = graph.landmarks.firstOrNull { it.nodeId == node.id }?.label
        return when (node.type) {
            NodeType.JUNCTION -> "at the junction"
            NodeType.ELEVATOR -> "at the elevator"
            NodeType.STAIRS -> "at the stairs"
            else -> label?.let { "at room $it" } ?: "where the corridor bends"
        }
    }
}

/** One-line text for the banner and voice, e.g. "Turn left in 12 steps". */
fun Instruction.headline(): String {
    val action = when (maneuver) {
        Maneuver.STRAIGHT -> "Go straight"
        Maneuver.TURN_LEFT -> "Turn left"
        Maneuver.TURN_RIGHT -> "Turn right"
        Maneuver.SLIGHT_LEFT -> "Bear left"
        Maneuver.SLIGHT_RIGHT -> "Bear right"
        Maneuver.U_TURN -> "Turn around"
        Maneuver.ARRIVE -> if (stepsUntil == 0) return "You have arrived" else "Arrive"
    }
    return "$action in $stepsUntil ${if (stepsUntil == 1) "step" else "steps"}"
}
