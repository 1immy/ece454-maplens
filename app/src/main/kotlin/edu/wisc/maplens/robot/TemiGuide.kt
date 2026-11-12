package edu.wisc.maplens.robot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import edu.wisc.maplens.R
import edu.wisc.maplens.guidance.Maneuver
import edu.wisc.maplens.guidance.RouteGuide
import edu.wisc.maplens.model.PixelPoint
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Runs one temi escort for the navigation screen: listens to the robot, feeds an
 * [EscortController] with the walker and the robot, turns its actions into temi commands
 * (through [MapAlignment]), and tells the screen what to show and say via [Listener].
 *
 * Used for live walks and for the demo walk, where the scripted walker follows temi
 * ([walkerMayStep]) so every behaviour (waiting, doors, arrival) can be shown at a desk with
 * the simulator.
 */
class TemiGuide(
    private val context: Context,
    guide: RouteGuide,
    val carry: Carry,
    private val pxPerMeter: Double,
    private val alignment: MapAlignment,
    /** "room 3418", for what temi says on arrival. */
    private val destination: String,
    /** Demo: add a door at the route's first turn if none is set up, to show door help. */
    private val demoDoor: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onRobot(at: PixelPoint, bearing: Double)
        fun onEscort(state: EscortState, gapMeters: Double?)

        /** A cue to show and speak on the phone ([cue] says which). */
        fun onCue(cue: EscortCue, phoneText: String)
    }

    private val params = EscortParams(
        leadMeters = RobotHub.setup.leadMeters,
        speed = RobotHub.setup.speed,
    )

    var controller = makeController(guide)
        private set

    val state: EscortState get() = controller.state

    private val main = Handler(Looper.getMainLooper())
    private var lastStepAt: Double? = null
    private var userAlong: Double? = null
    private var userAt: PixelPoint? = null
    private var pendingStatus: GoStatus? = null
    private var detected: Double? = null
    private var running = false

    private val onEvent: (RobotEvent) -> Unit = { e ->
        when (e) {
            is RobotEvent.Pose -> {
                listener.onRobot(alignment.toMap(e.x, e.y), alignment.toMapBearing(e.yaw))
            }
            is RobotEvent.GoToStatus -> pendingStatus = e.status
            is RobotEvent.Detection -> detected = if (e.detected) e.distance else null
            is RobotEvent.Button -> if (e.id == DOOR_BUTTON) doorOpened()
            else -> Unit
        }
        if (e !is RobotEvent.Pose || pendingStatus != null) tick()
    }

    private val timer = object : Runnable {
        override fun run() {
            tick()
            if (running) main.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        running = true
        RobotHub.addListener(onEvent)
        main.post(timer)
    }

    /** Ends the escort; temi stops unless it already arrived. */
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(timer)
        RobotHub.removeListener(onEvent)
        if (controller.state != EscortState.ARRIVED) RobotHub.send(RobotCommand.Stop)
        RobotHub.send(RobotCommand.Detection(false, 0.0))
        RobotHub.send(RobotCommand.Screen("", null))
    }

    /** The walker took a step (hand: with their progress along the route and position). */
    fun onWalker(alongPx: Double?, at: PixelPoint?) {
        lastStepAt = now()
        userAlong = alongPx
        userAt = at
        tick()
    }

    /** A new route (a reroute on the phone): temi follows it from where it is. */
    fun rerouted(guide: RouteGuide) {
        controller = makeController(guide)
        tick()
    }

    /** The walker opened the door (the phone's button, or temi's screen). */
    fun doorOpened() = run(controller.doorOpened())

    /**
     * Demo only: the scripted walker follows temi like a person would, staying a little
     * behind it, and goes up to it when it waits at a door.
     */
    fun walkerMayStep(walkerAlongPx: Double): Boolean {
        val robot = controller.robotAlongPx ?: return false
        // temi is at the room: the walker comes the last few steps up to the door.
        if (controller.state == EscortState.ARRIVED) return true
        return walkerAlongPx < robot - FOLLOW_BEHIND_M * pxPerMeter
    }

    fun tick() {
        if (!running) return
        val pose = RobotHub.pose ?: return
        val at = alignment.toMap(pose.x, pose.y)
        val status = pendingStatus
        pendingStatus = null
        val hand = carry == Carry.HAND
        val actions = controller.update(
            EscortInput(
                now = now(),
                robot = at,
                userAlongPx = if (hand) userAlong else null,
                user = if (hand) userAt else null,
                lastStepAt = lastStepAt,
                goStatus = status,
                detectedMeters = detected,
            ),
        )
        run(actions)
        listener.onEscort(controller.state, controller.gapMeters)
    }

    private fun run(actions: List<EscortAction>) {
        val pose = RobotHub.pose
        for (a in actions) {
            when (a) {
                is EscortAction.MoveTo -> {
                    val (x, y) = alignment.toRobot(a.point)
                    RobotHub.send(RobotCommand.GoTo(x, y, null, params.speed))
                }
                EscortAction.Stop -> RobotHub.send(RobotCommand.Stop)
                is EscortAction.Face -> {
                    val yaw = pose?.yaw ?: continue
                    val turn = wrap(alignment.toRobotYaw(a.bearing) - yaw)
                    val degrees = Math.toDegrees(turn).roundToInt()
                    if (kotlin.math.abs(degrees) >= MIN_TURN_DEG) {
                        RobotHub.send(RobotCommand.TurnBy(degrees))
                    }
                }
                is EscortAction.Detection ->
                    RobotHub.send(RobotCommand.Detection(a.on, DETECT_METERS))
                is EscortAction.Say -> say(a.cue)
            }
        }
    }

    private fun say(cue: EscortCue) {
        val (robotLine, phoneLine) = when (cue) {
            EscortCue.FOLLOW -> R.string.temi_line_follow to R.string.temi_cue_follow
            EscortCue.WAITING -> R.string.temi_line_waiting to R.string.temi_cue_waiting
            EscortCue.MEET -> R.string.temi_line_meet to R.string.temi_cue_meet
            EscortCue.CONTINUE -> R.string.temi_line_continue to R.string.temi_cue_follow
            EscortCue.OPEN_DOOR -> R.string.temi_line_door to R.string.temi_cue_door
            EscortCue.DOOR_THANKS -> R.string.temi_line_thanks to R.string.temi_cue_follow
            EscortCue.BLOCKED -> R.string.temi_line_blocked to R.string.temi_cue_blocked
            EscortCue.ARRIVED -> R.string.temi_line_arrived to R.string.temi_cue_arrived
        }
        RobotHub.send(RobotCommand.Speak(context.getString(robotLine, destination)))
        RobotHub.send(
            RobotCommand.Screen(
                context.getString(robotLine, destination),
                if (cue == EscortCue.OPEN_DOOR) DOOR_BUTTON else null,
            ),
        )
        listener.onCue(cue, context.getString(phoneLine, destination))
    }

    private fun makeController(guide: RouteGuide): EscortController {
        val path = guide.path
        val doorNodes = RobotHub.setup.doorNodes
        var doors = path.nodeAt.filter { (_, node) -> node in doorNodes }.keys
            .map { path.distances[it] }
        val turns = guide.cues.filter { it.maneuver != Maneuver.ARRIVE }.map { it.distancePx }
        if (doors.isEmpty() && demoDoor) {
            // Show the door behaviour: a door right after the first turn far enough in.
            doors = turns.filter { it > MIN_DEMO_DOOR_M * pxPerMeter }.take(1)
                .map { it + DEMO_DOOR_PAST_M * pxPerMeter }
                .filter { it < path.lengthPx - DEMO_DOOR_PAST_M * pxPerMeter }
        }
        return EscortController(path, doors, turns, carry, pxPerMeter, params)
    }

    private fun now() = SystemClock.uptimeMillis() / 1000.0

    private fun wrap(a: Double): Double {
        var r = a
        while (r > PI) r -= 2 * PI
        while (r < -PI) r += 2 * PI
        return r
    }

    companion object {
        /** The button temi's screen shows at a door; its id comes back in a button event. */
        const val DOOR_BUTTON = "door_open"
        private const val TICK_MS = 500L
        private const val FOLLOW_BEHIND_M = 1.2
        private const val DETECT_METERS = 3.0
        private const val MIN_TURN_DEG = 10
        private const val MIN_DEMO_DOOR_M = 4.0
        private const val DEMO_DOOR_PAST_M = 3.0
    }
}
