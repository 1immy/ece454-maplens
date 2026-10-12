package edu.wisc.maplens.model

enum class StepSource { STEP_DETECTOR, ACCEL_PEAK }

/**
 * Processed sensor output. Producer: sensor layer (Area B). Consumer: tracker.
 * Heading comes from the game rotation vector (no magnetometer), in map frame after
 * the user confirms which corridor they face.
 */
sealed interface MotionEvent {
    val timestampNanos: Long

    data class Step(
        override val timestampNanos: Long,
        val headingRad: Double,
        val source: StepSource,
    ) : MotionEvent

    data class Turn(
        override val timestampNanos: Long,
        val deltaRad: Double,
    ) : MotionEvent
}
