package edu.wisc.maplens

import edu.wisc.maplens.model.ConfidenceLevel
import edu.wisc.maplens.model.ConfidenceState
import edu.wisc.maplens.model.EvalCondition
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.Route
import edu.wisc.maplens.routing.Router
import edu.wisc.maplens.routing.routeToLabel

enum class Phase { CAPTURE, VERIFY, CALIBRATE, NAVIGATE, RECOVER, ARRIVED }

/**
 * The only writer of position and route. Implements the control flow
 * Capture -> Verify -> Calibrate -> Navigate <-> Recover -> Arrived.
 * Owned by Area C.
 */
class NavigationSession(
    private val router: Router,
    val condition: EvalCondition = EvalCondition.CONFIDENCE_TRIGGERED,
) {
    var phase: Phase = Phase.CAPTURE
        private set
    var graph: FloorGraph? = null
        private set
    var route: Route? = null
        private set
    var pose: PoseEstimate? = null
        private set
    var stepLengthPx: Double? = null
        private set

    fun onMapCaptured(unverified: FloorGraph) {
        check(phase == Phase.CAPTURE) { "capture only from CAPTURE, was $phase" }
        graph = unverified
        phase = Phase.VERIFY
    }

    /** Saving is blocked until the graph passes its structural checks. */
    fun onGraphVerified(verified: FloorGraph) {
        check(phase == Phase.VERIFY) { "verify only from VERIFY, was $phase" }
        val problems = verified.validate()
        require(problems.isEmpty()) { "graph not ready: $problems" }
        graph = verified.copy(verified = true)
        phase = Phase.CALIBRATE
    }

    /** Returns false if the destination is unreachable. */
    fun onCalibrated(k: Double, destinationNodeId: String): Boolean {
        check(phase == Phase.CALIBRATE) { "calibrate only from CALIBRATE, was $phase" }
        val g = checkNotNull(graph)
        stepLengthPx = k
        route = router.route(g, g.startNodeId, destinationNodeId) ?: return false
        phase = Phase.NAVIGATE
        return true
    }

    /** Like [onCalibrated], but routes to the nearest door of a room label. */
    fun onCalibratedToRoom(k: Double, roomLabel: String): Boolean {
        check(phase == Phase.CALIBRATE) { "calibrate only from CALIBRATE, was $phase" }
        val g = checkNotNull(graph)
        stepLengthPx = k
        route = router.routeToLabel(g, g.startNodeId, roomLabel) ?: return false
        phase = Phase.NAVIGATE
        return true
    }

    fun onPose(update: PoseEstimate) {
        pose = update
    }

    fun onConfidence(state: ConfidenceState) {
        if (condition != EvalCondition.CONFIDENCE_TRIGGERED) return
        phase = when {
            phase == Phase.NAVIGATE && state.level == ConfidenceLevel.LOW -> Phase.RECOVER
            phase == Phase.RECOVER && state.level == ConfidenceLevel.HIGH -> Phase.NAVIGATE
            else -> phase
        }
    }

    fun onArrived() {
        phase = Phase.ARRIVED
    }
}
