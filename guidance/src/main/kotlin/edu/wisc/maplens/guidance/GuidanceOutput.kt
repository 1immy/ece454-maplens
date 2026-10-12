package edu.wisc.maplens.guidance

import edu.wisc.maplens.model.ConfidenceState

enum class Maneuver { STRAIGHT, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, U_TURN, ARRIVE }

data class Instruction(
    val maneuver: Maneuver,
    val stepsUntil: Int,
    /** Short phrase such as "at the corridor junction". */
    val detail: String,
)

/** Arrow, map, voice and vibration all implement this. Owned by Area C. */
interface GuidanceOutput {
    fun show(instruction: Instruction)

    fun onConfidence(state: ConfidenceState)
}
