package edu.wisc.maplens.guidance

/** Vibration patterns the user can tell apart without looking (#20). */
enum class Haptic { TURN, ARRIVE }

/** One thing to say, and optionally feel, at this step. */
data class Announcement(val text: String, val haptic: Haptic?)

/** When to speak. Values in steps, so they scale with the walker's k. */
data class AnnouncerParams(
    /** Say "In N steps, turn left …" once the next maneuver is this close (and at the start). */
    val prepareSteps: Int = 12,
    /** Say "Turn left …" and vibrate once it is this close. */
    val nowSteps: Int = 2,
)

/**
 * Decides which [Instruction]s are worth speaking, so voice and vibration stay short and never
 * repeat every step: one heads-up per maneuver, one "now" with a vibration, one arrival.
 *
 * Pure Kotlin. Feed it every instruction with the index of the upcoming cue in
 * `RouteGuide.cues`; a new index means a new maneuver. Call [reset] for a new route.
 */
class CueAnnouncer(private val params: AnnouncerParams = AnnouncerParams()) {
    private var cue = -1
    private var started = false
    private var prepared = false
    private var nowSaid = false
    private var arrived = false

    fun reset() {
        cue = -1
        started = false
        prepared = false
        nowSaid = false
        arrived = false
    }

    fun next(instruction: Instruction, cueIndex: Int): Announcement? {
        val n = instruction.stepsUntil
        val arriving = instruction.maneuver == Maneuver.ARRIVE
        if (arriving && n == 0) {
            if (arrived) return null
            arrived = true
            return Announcement(arrivalText(instruction), Haptic.ARRIVE)
        }
        if (cueIndex != cue) {
            cue = cueIndex
            prepared = false
            nowSaid = false
        }
        val first = !started
        started = true
        if (!arriving && n <= params.nowSteps && !nowSaid) {
            nowSaid = true
            prepared = true
            return Announcement(nowText(instruction), Haptic.TURN)
        }
        if (!prepared && (first || n <= params.prepareSteps)) {
            prepared = true
            return Announcement(prepareText(instruction), null)
        }
        return null
    }

    companion object {
        fun prepareText(i: Instruction): String {
            val steps = if (i.stepsUntil == 1) "1 step" else "${i.stepsUntil} steps"
            return "In $steps, ${verb(i.maneuver)} ${i.detail}".trim()
        }

        fun nowText(i: Instruction): String =
            "${verb(i.maneuver).replaceFirstChar { it.uppercase() }} ${i.detail}".trim()

        fun arrivalText(i: Instruction): String = "You have arrived ${i.detail}".trim()

        private fun verb(m: Maneuver) = when (m) {
            Maneuver.STRAIGHT -> "continue straight"
            Maneuver.TURN_LEFT -> "turn left"
            Maneuver.TURN_RIGHT -> "turn right"
            Maneuver.SLIGHT_LEFT -> "bear left"
            Maneuver.SLIGHT_RIGHT -> "bear right"
            Maneuver.U_TURN -> "turn around"
            Maneuver.ARRIVE -> "arrive"
        }
    }
}
