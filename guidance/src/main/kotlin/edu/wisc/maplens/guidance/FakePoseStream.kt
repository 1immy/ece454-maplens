package edu.wisc.maplens.guidance

import edu.wisc.maplens.model.PoseEstimate
import kotlin.math.min

/**
 * Stand-in for the tracker (#13): walks a [RoutePath] one step of [stepLengthPx] at a time and
 * reports a perfect [PoseEstimate]. Lets the guidance UI run before the particle filter exists.
 */
class FakePoseStream(
    private val path: RoutePath,
    private val stepLengthPx: Double,
    private val stepIntervalNanos: Long = 600_000_000,
) {
    init {
        require(stepLengthPx > 0) { "step length must be positive" }
    }

    var stepsTaken = 0
        private set

    val finished: Boolean get() = stepsTaken * stepLengthPx >= path.lengthPx

    /** The pose before any step: at the first node, facing along the route. */
    fun current(): PoseEstimate {
        val s = min(stepsTaken * stepLengthPx, path.lengthPx)
        return PoseEstimate(
            timestampNanos = stepsTaken * stepIntervalNanos,
            position = path.pointAt(s),
            headingRad = path.bearingAt(s),
            edgeId = path.edgeAt(s),
            spreadPx = 0.0,
            topClusterWeight = 1.0,
            stepLengthPx = stepLengthPx,
        )
    }

    /** Takes one step and returns the new pose; stays at the end once [finished]. */
    fun step(): PoseEstimate {
        if (!finished) stepsTaken++
        return current()
    }
}
