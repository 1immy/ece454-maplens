package edu.wisc.maplens.robot

import edu.wisc.maplens.guidance.RoutePath
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.bearing
import kotlin.math.PI
import kotlin.math.abs

/** How the walker carries the phone while following temi. */
enum class Carry {
    /** In hand: the phone tracks the walker, so temi can pace itself to them. */
    HAND,

    /** In a pocket: the phone can't track them, so temi checks back with its own camera. */
    POCKET,
}

enum class EscortState { STARTING, LEADING, WAITING_FOR_USER, MEETING, DOOR, BLOCKED, ARRIVED }

/** Something to tell the walker (phone voice and banner) and say on temi. */
enum class EscortCue { FOLLOW, WAITING, MEET, CONTINUE, OPEN_DOOR, DOOR_THANKS, BLOCKED, ARRIVED }

/** Escort pacing, in metres and seconds. Starting values; tune on the EH3 walks. */
data class EscortParams(
    /** Hand: temi stays about this far ahead of the walker along the route. */
    val leadMeters: Double = 3.0,
    /** Hand: farther ahead than this, temi stops, turns to the walker and waits... */
    val maxGapMeters: Double = 6.0,
    /** ...until they are this close again and walking. */
    val resumeGapMeters: Double = 3.0,
    /** Hand: no footfall for this long means the walker stopped; temi waits facing them. */
    val stillSeconds: Double = 4.0,
    /** temi stops this far before a door the walker has to open. */
    val doorStopMeters: Double = 1.5,
    /** Pocket: temi walks at most this far, or to the next turn, before checking back. */
    val pocketLegMeters: Double = 8.0,
    /** Pocket: someone detected this close behind (in front of temi's camera) has caught up. */
    val meetMeters: Double = 2.0,
    /** temi counts as arrived this close to the end of the route. */
    val arriveMeters: Double = 1.0,
    /** A new go-to only when the target moved at least this much (spares the robot). */
    val recommandMeters: Double = 0.8,
    /** After temi gives up (an obstacle), try again after this long, at most [maxRetries] times. */
    val retrySeconds: Double = 5.0,
    val maxRetries: Int = 3,
    /** Walking-guide speed, m/s; temi accepts 0.3 to 1.5. */
    val speed: Double = 0.6,
)

/** What the controller wants done; the screen turns map points into temi's frame. */
sealed interface EscortAction {
    /** Drive to [point] (map px), [alongPx] along the route, facing [bearing] at the end. */
    data class MoveTo(
        val point: PixelPoint,
        val alongPx: Double,
        val bearing: Double
    ) : EscortAction

    data object Stop : EscortAction

    /** Turn on the spot to face map bearing [bearing] (toward the walker). */
    data class Face(val bearing: Double) : EscortAction

    data class Detection(val on: Boolean) : EscortAction

    data class Say(val cue: EscortCue) : EscortAction
}

/** One moment of the escort, as the phone sees it. */
data class EscortInput(
    /** Seconds, any monotonic clock. */
    val now: Double,
    /** temi on the MapLens map, or null before its first pose. */
    val robot: PixelPoint?,
    /** Hand: the walker's progress along the route and position; null in a pocket. */
    val userAlongPx: Double? = null,
    val user: PixelPoint? = null,
    /** When the walker last took a step (seconds), null if not yet. */
    val lastStepAt: Double? = null,
    /** temi's go-to status since the last update, if it reported one. */
    val goStatus: GoStatus? = null,
    /** Pocket: temi's camera sees someone [detectedMeters] away, or null if not. */
    val detectedMeters: Double? = null,
)

/**
 * Leads a walker along a MapLens route with temi, like a person showing the way (#temi):
 *
 * - **Hand:** temi keeps about [EscortParams.leadMeters] ahead of the walker. If they fall
 *   [EscortParams.maxGapMeters] behind or stop, temi stops, turns to face them and waits
 *   until they come within [EscortParams.resumeGapMeters] and walk on. It never runs off.
 * - **Pocket:** the phone can't track the walker, so temi leads one leg at a time (up to the
 *   next turn, a door, or [EscortParams.pocketLegMeters]), then turns round and uses its
 *   Detection Mode to wait until someone is within [EscortParams.meetMeters].
 * - **Doors:** temi stops [EscortParams.doorStopMeters] before each door on the route, turns
 *   to the walker and asks them to open it; it goes on only after [doorOpened].
 * - **Blocked:** if temi gives up (an obstacle), it says so and tries again a few times.
 *
 * temi's own navigation does the driving and the obstacle avoidance between targets; this
 * decides where the targets are and when to wait. Pure Kotlin: feed [update] at least every
 * pose and every robot event, and run the returned actions.
 */
class EscortController(
    val path: RoutePath,
    /** Distances along the route of doors the walker must open. */
    doorsAlongPx: List<Double>,
    /** Distances along the route of turns (pocket legs end there). */
    private val turnsAlongPx: List<Double>,
    val carry: Carry,
    private val pxPerMeter: Double,
    private val params: EscortParams = EscortParams(),
) {
    var state = EscortState.STARTING
        private set

    /** temi's progress along the route, px; null before its first pose. */
    var robotAlongPx: Double? = null
        private set

    /** How far temi is ahead of the walker along the route, m (hand only). */
    var gapMeters: Double? = null
        private set

    private val doors =
        doorsAlongPx.filter { it > 0 && it < path.lengthPx }.sorted().toMutableList()
    private var lastTarget: Double? = null
    private var legEnd: Double? = null
    private var blockedAt = 0.0
    private var retries = 0
    private var everStepped = false

    private val m get() = pxPerMeter

    /** The next door ahead of temi, if any (distance along the route). */
    val nextDoorPx: Double? get() = doors.firstOrNull()

    fun update(input: EscortInput): List<EscortAction> {
        if (state == EscortState.ARRIVED) return emptyList()
        val robot = input.robot ?: return emptyList()
        if (input.lastStepAt != null) everStepped = true
        val robotS = path.closest(robot, (robotAlongPx ?: 0.0) - 3 * m, Double.MAX_VALUE).alongPx
        robotAlongPx = robotS
        val userS = input.userAlongPx
        gapMeters = userS?.let { (robotS - it) / m }
        val out = mutableListOf<EscortAction>()

        if (robotS >= path.lengthPx - params.arriveMeters * m) {
            val userThere = carry == Carry.POCKET || userS == null ||
                userS >= path.lengthPx - params.resumeGapMeters * m
            if (userThere) {
                state = EscortState.ARRIVED
                out += faceWalker(robot, input, robotS)
                out += EscortAction.Say(EscortCue.ARRIVED)
                return out
            }
        }

        if (input.goStatus == GoStatus.ABORT &&
            (state == EscortState.LEADING || state == EscortState.STARTING)
        ) {
            state = EscortState.BLOCKED
            blockedAt = input.now
            retries++
            lastTarget = null
            return listOf(EscortAction.Say(EscortCue.BLOCKED))
        }
        if (state == EscortState.BLOCKED) {
            if (retries > params.maxRetries || input.now - blockedAt < params.retrySeconds) {
                return emptyList()
            }
            state = EscortState.LEADING
        }

        val door = nextDoorPx
        val doorStop = door?.let { it - params.doorStopMeters * m }
        if (state == EscortState.DOOR) return emptyList()
        if (doorStop != null && robotS >= doorStop - DOOR_SLACK_M * m) {
            state = EscortState.DOOR
            lastTarget = null
            out += EscortAction.Stop
            out += faceWalker(robot, input, robotS)
            out += EscortAction.Say(EscortCue.OPEN_DOOR)
            return out
        }

        return when (carry) {
            Carry.HAND -> lead(input, robot, robotS, userS, doorStop, out)
            Carry.POCKET -> leapfrog(input, robot, robotS, doorStop, out)
        }
    }

    /** The walker opened the door (button on the phone or on temi): go on through it. */
    fun doorOpened(): List<EscortAction> {
        if (state != EscortState.DOOR) return emptyList()
        doors.removeFirstOrNull()
        state = EscortState.LEADING
        lastTarget = null
        legEnd = null
        return listOf(EscortAction.Say(EscortCue.DOOR_THANKS))
    }

    /** Give up waiting for a blocked path (the walker chose to go on with the phone only). */
    val gaveUp: Boolean get() = state == EscortState.BLOCKED && retries > params.maxRetries

    private fun lead(
        input: EscortInput,
        robot: PixelPoint,
        robotS: Double,
        userS: Double?,
        doorStop: Double?,
        out: MutableList<EscortAction>,
    ): List<EscortAction> {
        // Without the walker's position, hold where we are.
        val walker = userS ?: return out
        val gap = (robotS - walker) / m
        val still = everStepped &&
            (input.lastStepAt == null || input.now - input.lastStepAt > params.stillSeconds)
        if (state == EscortState.WAITING_FOR_USER) {
            if (gap > params.resumeGapMeters || still) return out
            state = EscortState.LEADING
            out += EscortAction.Say(EscortCue.FOLLOW)
        } else if (gap > params.maxGapMeters || (still && gap > CLOSE_M)) {
            state = EscortState.WAITING_FOR_USER
            lastTarget = null
            out += EscortAction.Stop
            out += faceWalker(robot, input, robotS)
            out += EscortAction.Say(EscortCue.WAITING)
            return out
        }
        if (state == EscortState.STARTING) {
            state = EscortState.LEADING
            out += EscortAction.Say(EscortCue.FOLLOW)
        }
        var target = minOf(walker + params.leadMeters * m, path.lengthPx)
        if (doorStop != null) target = minOf(target, doorStop)
        target = maxOf(target, robotS)
        moveTo(target, out)
        return out
    }

    private fun leapfrog(
        input: EscortInput,
        robot: PixelPoint,
        robotS: Double,
        doorStop: Double?,
        out: MutableList<EscortAction>,
    ): List<EscortAction> {
        if (state == EscortState.MEETING) {
            val near = input.detectedMeters
            if (near == null || near > params.meetMeters) return out
            state = EscortState.LEADING
            out += EscortAction.Detection(false)
            out += EscortAction.Say(EscortCue.CONTINUE)
        }
        if (state == EscortState.STARTING) {
            state = EscortState.LEADING
            out += EscortAction.Say(EscortCue.FOLLOW)
        }
        val end = legEnd ?: listOfNotNull(
            robotS + params.pocketLegMeters * m,
            turnsAlongPx.firstOrNull { it > robotS + MIN_LEG_M * m },
            doorStop?.takeIf { it > robotS },
            path.lengthPx,
        ).min().also {
            legEnd = it
            lastTarget = null
        }
        val reached = robotS >= end - LEG_SLACK_M * m ||
            (input.goStatus == GoStatus.COMPLETE && robotS >= end - 2 * LEG_SLACK_M * m)
        if (reached && end < path.lengthPx - params.arriveMeters * m) {
            state = EscortState.MEETING
            legEnd = null
            lastTarget = null
            // The walker is behind: turn round so temi's camera can see them catch up.
            out += EscortAction.Face(wrap(path.bearingAt(robotS) + PI))
            out += EscortAction.Detection(true)
            out += EscortAction.Say(EscortCue.MEET)
            return out
        }
        moveTo(end, out)
        return out
    }

    private fun moveTo(target: Double, out: MutableList<EscortAction>) {
        val last = lastTarget
        if (last != null && abs(target - last) < params.recommandMeters * m) return
        lastTarget = target
        val facing = path.bearingAt(minOf(target, path.lengthPx - 1e-6))
        out += EscortAction.MoveTo(path.pointAt(target), target, facing)
    }

    /** Toward the walker if the phone knows where they are, else back along the route. */
    private fun faceWalker(robot: PixelPoint, input: EscortInput, robotS: Double): EscortAction {
        val user = input.user
        val toward = if (user != null && user.distanceTo(robot) > 0.3 * m) {
            bearing(robot, user)
        } else {
            wrap(path.bearingAt(robotS) + PI)
        }
        return EscortAction.Face(toward)
    }

    private fun wrap(a: Double): Double {
        var r = a
        while (r > PI) r -= 2 * PI
        while (r < -PI) r += 2 * PI
        return r
    }

    private companion object {
        /** Start the door stop this early, so temi doesn't overshoot it. */
        const val DOOR_SLACK_M = 0.4
        const val LEG_SLACK_M = 0.4

        /** A walker this close counts as caught up even if stopped. */
        const val CLOSE_M = 1.5

        /** A pocket leg is at least this long, so temi doesn't stop twice at one turn. */
        const val MIN_LEG_M = 1.5
    }
}
