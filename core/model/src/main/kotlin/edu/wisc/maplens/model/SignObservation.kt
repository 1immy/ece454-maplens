package edu.wisc.maplens.model

/**
 * A room-sign read by the camera. Producer: sign scanner (Area A). Consumer: tracker,
 * which accepts it only if the matched door lies within max(3 sigma, 5 m) of the cloud.
 */
data class SignObservation(
    val timestampNanos: Long,
    val text: String,
    val ocrConfidence: Float,
    /** Door node whose landmark label matched [text], or null if no match. */
    val matchedNodeId: String?,
)
