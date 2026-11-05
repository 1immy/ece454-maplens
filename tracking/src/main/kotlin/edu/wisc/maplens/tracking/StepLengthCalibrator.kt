package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.CorridorOption
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.bearingDifference
import edu.wisc.maplens.model.corridorsAt
import kotlin.math.abs

/**
 * Learns k from the opening legs of a walk, with no extra screen (#12).
 *
 * The walker leaves START along the corridor they said they face. Each step's heading is
 * compared with that corridor; a turn is a step more than `calibrationTurnDeg` off it, kept for
 * `calibrationTurnConfirmSteps` steps. The turn is placed at a node on the straight corridor
 * ahead that has an exit in the new direction, and the next leg starts there. At the first such
 * turn reached after `calibrationMinSteps` steps,
 * `k = graph px from START to that node ÷ steps before the turn` ([StepLength.calibrate]).
 *
 * Short legs are walked through, not used: on EH3 the first turn is 4 steps from START, where
 * one step of timing is 25% of k; the second is about 27 steps out, where it is about 4%.
 *
 * The result also says where the walker is: on the turn's exit corridor, [Calibration.stepsPast]
 * steps past its node, which lets the tracker re-place a cloud that ran ahead or fell behind
 * on a wrong starting k.
 *
 * Gives up (returns null for good) rather than guess when no node fits the turn, when two
 * nodes on the corridor both fit the step count, or when k comes out more than
 * `calibrationMaxChange` from the starting k. Then the tracker keeps its starting k.
 */
class StepLengthCalibrator(
    private val graph: FloorGraph,
    startNodeId: String,
    initialHeadingRad: Double,
    private val initialStepLengthPx: Double,
    private val config: TrackingConfig = TrackingConfig(),
) {
    private val turnRad = Math.toRadians(config.calibrationTurnDeg)

    /** Node the current leg started from, its corridor, and graph px from START to it. */
    private var legNode = startNodeId
    private var leg: CorridorOption? = graph.corridorsAt(startNodeId)
        .minByOrNull { bearingDifference(it.bearingRad, initialHeadingRad) }
    private var legStartPx = 0.0

    private var steps = 0

    /** Steps before the first step of a possible turn, or null while on the leg. */
    private var turnAt: Int? = null
    private var turnSteps = 0

    /** True once k was found or calibration gave up; later steps are ignored. */
    var finished = false
        private set

    /** Calibrated k, and the corridor the walker turned into [stepsPast] steps ago. */
    data class Calibration(
        val stepLengthPx: Double,
        val turn: CorridorOption,
        val stepsPast: Int,
    )

    /** Feeds one step's map heading. Returns the calibration once, then null. */
    fun onStep(headingRad: Double): Calibration? {
        val current = leg
        if (finished || current == null) {
            finished = true
            return null
        }
        steps++
        if (bearingDifference(headingRad, current.bearingRad) <= turnRad) {
            turnAt = null
            turnSteps = 0
            return null
        }
        if (turnAt == null) turnAt = steps - 1
        if (++turnSteps < config.calibrationTurnConfirmSteps) return null
        return onTurn(turnAt!!, headingRad)
    }

    /** Places a confirmed turn on the graph; calibrates or moves on to the next leg. */
    private fun onTurn(stepsBefore: Int, headingRad: Double): Calibration? {
        val current = leg!!
        val candidates = straightRun(current).mapNotNull { (node, px) ->
            val exit = graph.corridorsAt(node)
                .minByOrNull { bearingDifference(it.bearingRad, headingRad) }
                ?.takeIf { bearingDifference(it.bearingRad, headingRad) <= turnRad }
            exit?.let { Triple(node, legStartPx + px, it) }
        }
        // Several turn-offs on this corridor: keep those the step count could reach at about
        // the starting k. One turn-off is taken as is; walkers cut corners on short legs.
        val fitting = if (candidates.size <= 1) {
            candidates
        } else {
            candidates.filter { (_, px, _) ->
                val expected = px / initialStepLengthPx
                abs(expected - stepsBefore) <=
                    maxOf(config.calibrationMaxChange * expected, config.calibrationStepSlack)
            }
        }
        if (fitting.size != 1 || stepsBefore == 0) {
            finished = true
            return null
        }
        val (node, px, exit) = fitting.single()
        turnAt = null
        turnSteps = 0
        if (stepsBefore >= config.calibrationMinSteps) {
            finished = true
            val k = StepLength.calibrate(px, stepsBefore)
            // A k far from the starting one means the turn was placed wrongly, not a new walker.
            val change = abs(k / initialStepLengthPx - 1)
            if (change > config.calibrationMaxChange) return null
            return Calibration(k, exit, steps - stepsBefore)
        }
        legNode = node
        leg = exit
        legStartPx = px
        return null
    }

    /**
     * Nodes along [option]'s corridor from [legNode], with px from [legNode], continuing through
     * nodes while an exit carries on within `calibrationTurnDeg` of the leg's bearing.
     */
    private fun straightRun(option: CorridorOption): List<Pair<String, Double>> = buildList {
        val edgesById = graph.edges.associateBy { it.id }
        var from = legNode
        var step: CorridorOption = option
        var px = 0.0
        val seen = mutableSetOf(from)
        while (true) {
            px += edgesById.getValue(step.edgeId).lengthPx
            val node = step.towardNodeId
            if (!seen.add(node)) return@buildList
            add(node to px)
            step = graph.corridorsAt(node)
                .filter { it.towardNodeId != from }
                .filter { bearingDifference(it.bearingRad, option.bearingRad) <= turnRad }
                .minByOrNull { bearingDifference(it.bearingRad, option.bearingRad) }
                ?: return@buildList
            from = node
        }
    }
}
