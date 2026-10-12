package edu.wisc.maplens.model

/**
 * Particle-filter summary after each step. Producer: tracker (Area B).
 * Consumers: confidence monitor, guidance.
 */
data class PoseEstimate(
    val timestampNanos: Long,
    val position: PixelPoint,
    val headingRad: Double,
    /** Edge the weighted mean snaps to, or null if between clusters. */
    val edgeId: String?,
    /** Weighted standard deviation of particle positions. */
    val spreadPx: Double,
    /** Share of total weight held by the largest particle cluster, 0..1. */
    val topClusterWeight: Double,
    /** Current blended scale k, pixels per step. */
    val stepLengthPx: Double,
)
