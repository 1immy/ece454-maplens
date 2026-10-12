package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.SignObservation

/** Map-constrained particle filter. Owned by Area B. */
interface Tracker {
    fun start(
        graph: FloorGraph,
        startNodeId: String,
        initialHeadingRad: Double,
        stepLengthPx: Double,
    )

    /** Predict, weigh, resample. Called off the UI thread. */
    fun onMotion(event: MotionEvent): PoseEstimate

    /** Returns true if the sign was accepted and the cloud re-anchored onto its door. */
    fun onSign(observation: SignObservation): Boolean
}
