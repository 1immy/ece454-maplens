package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.ConfidenceCheck
import edu.wisc.maplens.model.ConfidenceLevel
import edu.wisc.maplens.model.ConfidenceState
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.RecoveryAction
import edu.wisc.maplens.model.bearingDifference
import edu.wisc.maplens.model.bearingOnEdge
import edu.wisc.maplens.model.corridorsAt
import edu.wisc.maplens.model.nearestEdge
import kotlin.math.PI

/**
 * The four confidence checks (#18; `project-AI-context.md`, A5). Confidence is LOW when any
 * check fails; every threshold is in [TrackingConfig].
 *
 * | Check | LOW when |
 * |---|---|
 * | [ConfidenceCheck.SPREAD] | particle spread > `maxSpreadMeters` (px → m via k and stride) |
 * | [ConfidenceCheck.GROUP_SPLIT] | heaviest group holds < `minTopClusterWeight` of the weight |
 * | [ConfidenceCheck.HEADING_FIT] | step heading is on average > `maxHeadingErrorDeg` off the corridor under the estimate, over the last `headingWindowSteps` steps |
 * | [ConfidenceCheck.TURN_FIT] | the heading turned > `turnMinDeg` within `turnWindowSteps` steps (held for `turnConfirmSteps`) and no corridor near the estimate fits the new heading |
 *
 * A failed turn stays failed until a step's heading fits the estimate's corridor again, so
 * the session sees it for longer than the one step it happened on.
 *
 * Suggested recovery: [RecoveryAction.SCAN_SIGN] only while LOW, when a door landmark lies
 * within `max(scanAcceptSigmaMultiplier · σ, scanAcceptMinMeters)` of the estimate, and at
 * least `scanCooldownSteps` steps after the last scan was offered; once offered it stays
 * suggested until confidence is HIGH again, so two LOW spells close together get one prompt. Otherwise the next rung
 * of the fallback ladder, [RecoveryAction.NEXT_TURN_OR_DEAD_END]; climbing further (ask the
 * hallway, tap, map only) needs the user and is the session's job.
 *
 * Stateful: call [evaluate] once per step, in order, from the navigation worker that owns the
 * tracker. Call [reset] after the cloud is re-anchored (sign, tap) or restarted.
 */
class FourCheckConfidenceMonitor(
    private val config: TrackingConfig = TrackingConfig(),
) : ConfidenceMonitor {
    private val headingErrors = ArrayDeque<Double>()
    private val headings = ArrayDeque<Double>()
    private var turnFailed = false
    private var step = 0

    /** Step a scan was last offered on; null if never. */
    private var lastScanStep: Int? = null

    /** The current LOW episode has been offered a scan; it stays offered until HIGH. */
    private var episodeOfferedScan = false

    override fun evaluate(graph: FloorGraph, pose: PoseEstimate): ConfidenceState {
        step++
        val pxPerMeter = pose.stepLengthPx / config.nominalStrideMeters
        val failed = mutableSetOf<ConfidenceCheck>()

        if (pose.spreadPx > config.maxSpreadMeters * pxPerMeter) failed += ConfidenceCheck.SPREAD
        if (pose.topClusterWeight < config.minTopClusterWeight) {
            failed += ConfidenceCheck.GROUP_SPLIT
        }

        val misfit = corridorMisfit(graph, pose)
        headingErrors.addLast(misfit)
        while (headingErrors.size > config.headingWindowSteps) headingErrors.removeFirst()
        if (headingErrors.size == config.headingWindowSteps &&
            headingErrors.average() > Math.toRadians(config.maxHeadingErrorDeg)
        ) {
            failed += ConfidenceCheck.HEADING_FIT
        }

        if (turnFailed && misfit <= Math.toRadians(config.maxHeadingErrorDeg)) turnFailed = false
        headings.addLast(pose.headingRad)
        while (headings.size > config.turnWindowSteps + 1) headings.removeFirst()
        // A turn: the last turnConfirmSteps headings are all far from the one the window
        // started on, so a single wobbly step is not a turn.
        val turned = headings.size > config.turnConfirmSteps &&
            headings.takeLast(config.turnConfirmSteps).all {
                bearingDifference(headings.first(), it) > Math.toRadians(config.turnMinDeg)
            }
        if (turned) {
            // One turn is checked once: later steps compare against the new direction.
            headings.clear()
            headings.addLast(pose.headingRad)
            if (!turnFits(graph, pose)) turnFailed = true
        }
        if (turnFailed) failed += ConfidenceCheck.TURN_FIT

        val low = failed.isNotEmpty()
        val recovery = if (low) recovery(graph, pose, pxPerMeter) else null
        if (!low) episodeOfferedScan = false
        return ConfidenceState(
            timestampNanos = pose.timestampNanos,
            level = if (low) ConfidenceLevel.LOW else ConfidenceLevel.HIGH,
            failedChecks = failed,
            suggestedRecovery = recovery,
        )
    }

    /** Forgets the heading and turn history, e.g. after a sign or tap re-anchors the cloud. */
    fun reset() {
        headingErrors.clear()
        headings.clear()
        turnFailed = false
    }

    private fun recovery(graph: FloorGraph, pose: PoseEstimate, pxPerMeter: Double) =
        when {
            episodeOfferedScan -> RecoveryAction.SCAN_SIGN
            cooledDown() && signWithinReach(graph, pose, pxPerMeter) -> {
                episodeOfferedScan = true
                lastScanStep = step
                RecoveryAction.SCAN_SIGN
            }
            else -> RecoveryAction.NEXT_TURN_OR_DEAD_END
        }

    private fun cooledDown(): Boolean {
        val last = lastScanStep ?: return true
        return step - last >= config.scanCooldownSteps
    }

    /** A labelled door within the scan acceptance gate of the estimate. */
    private fun signWithinReach(
        graph: FloorGraph,
        pose: PoseEstimate,
        pxPerMeter: Double
    ): Boolean {
        val reach = maxOf(
            config.scanAcceptSigmaMultiplier * pose.spreadPx,
            config.scanAcceptMinMeters * pxPerMeter,
        )
        return graph.landmarks.any { landmark ->
            val node = graph.nodes[landmark.nodeId] ?: return@any false
            landmark.label.isNotBlank() && node.type == NodeType.DOOR &&
                node.position.distanceTo(pose.position) <= reach
        }
    }

    /**
     * How far the step heading is off the corridor under the estimate, either way along it, in
     * radians. Near a node every corridor there counts, so a step just round a corner fits.
     */
    private fun corridorMisfit(graph: FloorGraph, pose: PoseEstimate): Double {
        val on = graph.nearestEdge(pose.position) ?: return 0.0
        val bearings = mutableListOf(graph.bearingOnEdge(on.edgeId, on.offsetPx, towardTo = true))
        val edge = graph.edges.first { it.id == on.edgeId }
        val near = config.nodeSnapSteps * pose.stepLengthPx
        if (on.offsetPx <= near) bearings += graph.corridorsAt(edge.from).map { it.bearingRad }
        if (edge.lengthPx - on.offsetPx <= near) {
            bearings += graph.corridorsAt(edge.to).map { it.bearingRad }
        }
        return bearings.minOf { axisDifference(pose.headingRad, it) }
    }

    /**
     * True if the new heading fits the estimate's own corridor, or a corridor leaving any node
     * within `max(2σ, turnSearchSteps · k)` of the estimate.
     */
    private fun turnFits(graph: FloorGraph, pose: PoseEstimate): Boolean {
        val limit = Math.toRadians(config.turnBranchMaxDeg)
        val on = graph.nearestEdge(pose.position) ?: return true
        val along = graph.bearingOnEdge(on.edgeId, on.offsetPx, towardTo = true)
        if (axisDifference(pose.headingRad, along) <= limit) return true
        val radius = maxOf(2 * pose.spreadPx, config.turnSearchSteps * pose.stepLengthPx)
        return graph.nodes.values
            .filter { it.position.distanceTo(pose.position) <= radius }
            .any { node ->
                graph.corridorsAt(node.id).any {
                    bearingDifference(pose.headingRad, it.bearingRad) <= limit
                }
            }
    }

    /** Angle between a heading and a corridor walked either way, 0..π/2. */
    private fun axisDifference(heading: Double, corridor: Double): Double =
        minOf(bearingDifference(heading, corridor), bearingDifference(heading, corridor + PI))
}
