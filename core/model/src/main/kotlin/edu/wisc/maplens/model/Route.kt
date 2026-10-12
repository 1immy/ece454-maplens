package edu.wisc.maplens.model

/**
 * Ordered path through a [FloorGraph]. Producer: router (Area C). Consumer: guidance.
 */
data class Route(
    val nodeIds: List<String>,
    val edgeIds: List<String>,
    val lengthPx: Double,
) {
    /** Distance in steps given the calibrated scale k (pixels per step). */
    fun lengthSteps(stepLengthPx: Double): Double = lengthPx / stepLengthPx
}
