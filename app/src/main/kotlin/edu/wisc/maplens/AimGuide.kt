package edu.wisc.maplens

import edu.wisc.maplens.mapparse.PosterSighting
import edu.wisc.maplens.mapparse.SightingProblem
import kotlin.math.abs

/**
 * When the capture screen asks the user to move. Loose on purpose: every straight-on fixture
 * photo is within 13° of the wall, the "left30", "right30" and "down" shots are 22–37° off
 * (Oct 12 set, measured Nov 14). Anything below [angledDegrees] can be captured without a
 * warning; the screen only calls it ideal below [straightDegrees].
 */
internal data class AimParams(
    val straightDegrees: Double = 10.0,
    val angledDegrees: Double = 18.0,
    /**
     * A photo (taken or imported) more than this off the wall is sent back in user mode, before
     * the parse: the Oct 12 "left30", "right30" and "down" shots are 22–37°, every straight-on
     * one 13° or less.
     */
    val rejectDegrees: Double = 20.0,
    /** A state is left only this far past its threshold, so the hint doesn't flicker. */
    val hysteresisDegrees: Double = 2.0,
    /** Share of the frame the map should span; below it, ask to move closer. */
    val closerFill: Double = 0.5,
    val closerClearFill: Double = 0.55,
    /** A corner this close to the frame's edge (share of its size) may be cut off. */
    val edgeMargin: Double = 0.015,
    /** Weight of the newest look in the running average of angles, fill and corners. */
    val smoothing: Double = 0.4,
    /** How long a lost frame keeps the last good hint, so one missed look doesn't flash. */
    val lostMs: Long = 700,
    /** Turning faster than this (rad/s) blurs the photo. */
    val steadyRadPerSecond: Double = 0.6,
)

internal enum class AimHint {
    FIND,
    GLARE,
    STEP_BACK,
    STEP_LEFT,
    STEP_RIGHT,
    RAISE,
    LOWER,
    MOVE_CLOSER,
    HOLD_STEADY,
    READY
}

internal enum class AimTone { NEUTRAL, WARN, GOOD }

/**
 * What the capture screen shows: a [hint] in a [tone], the map's outline ([corners], TL TR BR
 * BL in a [width] × [height] frame, smoothed) and how far to move, in degrees: [moveX] > 0 means
 * step right, [moveY] > 0 means lower the phone.
 */
internal data class AimState(
    val hint: AimHint,
    val tone: AimTone,
    val width: Int = 0,
    val height: Int = 0,
    val corners: DoubleArray? = null,
    val moveX: Double = 0.0,
    val moveY: Double = 0.0,
) {
    /** Angled enough that user mode holds the shutter until the user moves. */
    val blocksCapture: Boolean
        get() = tone == AimTone.WARN &&
            hint in setOf(AimHint.STEP_LEFT, AimHint.STEP_RIGHT, AimHint.RAISE, AimHint.LOWER)
}

/** Which way to move for a map seen at [yaw] and [pitch] degrees (see [PosterSighting]). */
internal fun moveFor(yaw: Double, pitch: Double): AimHint = if (abs(yaw) >= abs(pitch)) {
    if (yaw < 0) AimHint.STEP_RIGHT else AimHint.STEP_LEFT
} else {
    if (pitch < 0) AimHint.LOWER else AimHint.RAISE
}

/** Turns a stream of preview looks into one steady instruction. Not thread-safe. */
internal class AimGuide(private val params: AimParams = AimParams()) {
    private var yaw = 0.0
    private var pitch = 0.0
    private var fill = 0.0
    private var corners: DoubleArray? = null
    private var lastSeenMs = Long.MIN_VALUE
    private var angled = false
    private var straight = false
    private var close = true
    private var last = AimState(AimHint.FIND, AimTone.NEUTRAL)

    fun update(sighting: PosterSighting?, nowMs: Long, turnRate: Double = 0.0): AimState {
        val found = sighting?.corners
        if (sighting == null || found == null) {
            if (corners != null && nowMs - lastSeenMs <= params.lostMs) return last
            corners = null
            val hint = when (sighting?.problem) {
                SightingProblem.CUT_OFF -> AimHint.STEP_BACK
                SightingProblem.OBSCURED -> AimHint.GLARE
                else -> AimHint.FIND
            }
            return AimState(hint, if (hint == AimHint.FIND) AimTone.NEUTRAL else AimTone.WARN)
                .also { last = it }
        }
        val fresh = corners == null || corners?.size != found.size
        val a = if (fresh) 1.0 else params.smoothing
        yaw += a * (sighting.yawDegrees - yaw)
        pitch += a * (sighting.pitchDegrees - pitch)
        fill += a * (sighting.fill - fill)
        corners = if (fresh) {
            found.copyOf()
        } else {
            DoubleArray(found.size) { corners!![it] + a * (found[it] - corners!![it]) }
        }
        lastSeenMs = nowMs
        val off = maxOf(abs(yaw), abs(pitch))
        angled = if (angled) {
            off > params.angledDegrees - params.hysteresisDegrees
        } else {
            off > params.angledDegrees
        }
        straight = if (straight) {
            off < params.straightDegrees + params.hysteresisDegrees
        } else {
            off < params.straightDegrees
        }
        close = if (close) fill >= params.closerFill else fill >= params.closerClearFill
        val (hint, tone) = when {
            nearEdge(found, sighting.width, sighting.height) -> AimHint.STEP_BACK to AimTone.WARN
            angled -> moveHint() to AimTone.WARN
            !close -> AimHint.MOVE_CLOSER to AimTone.NEUTRAL
            !straight -> moveHint() to AimTone.NEUTRAL
            turnRate > params.steadyRadPerSecond -> AimHint.HOLD_STEADY to AimTone.GOOD
            else -> AimHint.READY to AimTone.GOOD
        }
        return AimState(
            hint,
            tone,
            sighting.width,
            sighting.height,
            corners?.copyOf(),
            -yaw,
            -pitch,
        ).also { last = it }
    }

    /** Negative yaw: the phone is left of the map. Negative pitch: it is above the map. */
    private fun moveHint(): AimHint = moveFor(yaw, pitch)

    private fun nearEdge(corners: DoubleArray, width: Int, height: Int): Boolean {
        val dx = params.edgeMargin * width
        val dy = params.edgeMargin * height
        return corners.indices.any { i ->
            val value = corners[i]
            if (i % 2 == 0) value < dx || value > width - dx else value < dy || value > height - dy
        }
    }
}
