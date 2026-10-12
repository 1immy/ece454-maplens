package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.ConfidenceState
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PoseEstimate

/** Runs the four confidence checks after every step. Owned by Area B. */
interface ConfidenceMonitor {
    fun evaluate(graph: FloorGraph, pose: PoseEstimate): ConfidenceState
}
