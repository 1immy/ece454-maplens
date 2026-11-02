package edu.wisc.maplens.live

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.SignObservation
import edu.wisc.maplens.model.bearingDifference
import edu.wisc.maplens.model.bearingOnEdge
import edu.wisc.maplens.model.corridorsAt
import edu.wisc.maplens.model.edge
import edu.wisc.maplens.model.pointOnEdge
import edu.wisc.maplens.tracking.Tracker
import kotlin.math.PI

/**
 * **Stand-in for the particle tracker (#13)**, so live steps can drive the app before it lands.
 *
 * A single walker on the corridor graph: each step moves it k pixels along its corridor. It
 * turns around when the step heading points back the way it came, and at a node it takes the
 * corridor whose bearing best matches the step heading. No noise, no weights, no recovery: one
 * wrong branch and it stays wrong. Swap in the real [Tracker] by changing one constructor call
 * in `MainActivity`; nothing else depends on this class.
 *
 * Not thread safe: the live sensor worker owns it, like the real tracker.
 */
class GraphWalker : Tracker {
    private lateinit var graph: FloorGraph
    private lateinit var edge: Edge

    /** Distance from edge.from, in [0, edge.lengthPx]. */
    private var offset = 0.0

    /** Walking from edge.from toward edge.to. */
    private var forward = true
    private var headingRad = 0.0
    private var stepLengthPx = 0.0
    private var lastTimestamp = 0L

    override fun start(
        graph: FloorGraph,
        startNodeId: String,
        initialHeadingRad: Double,
        stepLengthPx: Double,
    ) {
        require(stepLengthPx > 0) { "step length must be positive" }
        this.graph = graph
        this.stepLengthPx = stepLengthPx
        headingRad = initialHeadingRad
        enter(startNodeId, initialHeadingRad)
    }

    override fun onMotion(event: MotionEvent): PoseEstimate {
        lastTimestamp = event.timestampNanos
        if (event is MotionEvent.Step) {
            headingRad = event.headingRad
            walk(event.headingRad)
        }
        return pose()
    }

    /** Sign re-anchoring is #19; the stand-in never accepts a sign. */
    override fun onSign(observation: SignObservation): Boolean = false

    fun pose() = PoseEstimate(
        timestampNanos = lastTimestamp,
        position = graph.pointOnEdge(edge.id, offset),
        headingRad = headingRad,
        edgeId = edge.id,
        spreadPx = 0.0,
        topClusterWeight = 1.0,
        stepLengthPx = stepLengthPx,
    )

    /** Leaves [nodeId] along the corridor closest to [heading]. */
    private fun enter(nodeId: String, heading: Double) {
        val best = graph.corridorsAt(nodeId)
            .filter { graph.edge(it.edgeId).let { e -> e.from != e.to } }
            .minByOrNull { bearingDifference(heading, it.bearingRad) }
            ?: throw IllegalArgumentException("node $nodeId has no corridor")
        edge = graph.edge(best.edgeId)
        forward = edge.from == nodeId
        offset = if (forward) 0.0 else edge.lengthPx
    }

    private fun walk(heading: Double) {
        val along = graph.bearingOnEdge(edge.id, offset, forward)
        if (bearingDifference(heading, along) > PI / 2) forward = !forward
        var left = stepLengthPx
        // Bounded so a run of zero-length edges can never spin forever.
        repeat(graph.edges.size + 1) {
            val toEnd = if (forward) edge.lengthPx - offset else offset
            if (left <= toEnd) {
                offset += if (forward) left else -left
                return
            }
            left -= toEnd
            enter(if (forward) edge.to else edge.from, heading)
        }
    }
}
