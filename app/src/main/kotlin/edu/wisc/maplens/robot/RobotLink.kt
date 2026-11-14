package edu.wisc.maplens.robot

import android.os.Handler
import android.os.Looper
import edu.wisc.maplens.model.PixelPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Connection to a guide robot. Events are delivered on the main thread. */
interface RobotLink {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    val name: String
    val state: State
    var onEvent: ((RobotEvent) -> Unit)?
    var onState: ((State) -> Unit)?

    fun connect()
    fun disconnect()
    fun send(command: RobotCommand)
}

/**
 * The real temi, through the MapLens companion app running on it (`docs/temi-integration.md`):
 * a TCP socket on the local network, [RobotProtocol] lines both ways. Reconnecting is the
 * caller's choice; a dropped link reports [RobotLink.State.DISCONNECTED].
 */
class TcpRobotLink(private val host: String, private val port: Int) : RobotLink {
    override val name get() = "temi at $host:$port"
    override var state = RobotLink.State.DISCONNECTED
        private set
    override var onEvent: ((RobotEvent) -> Unit)? = null
    override var onState: ((RobotLink.State) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val reader = Executors.newSingleThreadExecutor()

    @Volatile private var socket: Socket? = null

    @Volatile private var out: OutputStreamWriter? = null

    override fun connect() {
        if (state != RobotLink.State.DISCONNECTED) return
        setState(RobotLink.State.CONNECTING)
        io.execute {
            val s = runCatching {
                // Build the address outside apply {}: inside it, `port` would be Socket.port (0).
                val address = InetSocketAddress(host, port)
                Socket().apply { connect(address, CONNECT_TIMEOUT_MS) }
            }.getOrElse {
                main.post {
                    onEvent?.invoke(
                        RobotEvent.Error("Can't reach $host:$port (${it.message})")
                    )
                }
                main.post { setState(RobotLink.State.DISCONNECTED) }
                return@execute
            }
            socket = s
            out = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            main.post { setState(RobotLink.State.CONNECTED) }
            write(RobotProtocol.encode(RobotCommand.Hello("MapLens")))
            reader.execute { readLoop(s) }
        }
    }

    private fun readLoop(s: Socket) {
        runCatching {
            BufferedReader(
                InputStreamReader(s.getInputStream(), Charsets.UTF_8)
            ).forEachLine { line ->
                RobotProtocol.decodeEvent(line)?.let { e -> main.post { onEvent?.invoke(e) } }
            }
        }
        if (socket === s) main.post { closed() }
    }

    override fun disconnect() {
        io.execute {
            runCatching { socket?.close() }
            socket = null
            out = null
        }
        closed()
    }

    private fun closed() {
        socket = null
        if (state != RobotLink.State.DISCONNECTED) setState(RobotLink.State.DISCONNECTED)
    }

    override fun send(command: RobotCommand) {
        if (state != RobotLink.State.CONNECTED) return
        val line = RobotProtocol.encode(command)
        io.execute { write(line) }
    }

    private fun write(line: String) {
        val w = out ?: return
        runCatching {
            w.write(line)
            w.write("\n")
            w.flush()
        }.onFailure { main.post { closed() } }
    }

    private fun setState(next: RobotLink.State) {
        state = next
        onState?.invoke(next)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
    }
}

/**
 * A pretend temi for building and demoing without the robot. It drives in temi's frame
 * (metres) at the commanded speed, turns, reports its pose five times a second and its go-to
 * status like the SDK, and "sees" the walker ([userAt], map px) when they are within
 * [detectMeters] in front of it, like Detection Mode. Speech is handed to [onSpeak].
 */
class SimulatedTemi(
    private val alignment: MapAlignment,
    start: PixelPoint,
    private val userAt: () -> PixelPoint?,
    private val onSpeak: (String) -> Unit = {},
    private val detectMeters: Double = 3.0,
) : RobotLink {
    override val name = "temi simulator"
    override var state = RobotLink.State.DISCONNECTED
        private set
    override var onEvent: ((RobotEvent) -> Unit)? = null
    override var onState: ((RobotLink.State) -> Unit)? = null

    // Lazy so [step] runs in plain JVM unit tests.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    var x: Double
        private set
    var y: Double
        private set
    var yaw = 0.0
        private set
    private var target: Pair<Double, Double>? = null
    private var speed = RobotProtocol.DEFAULT_SPEED
    private var turnLeft = 0.0
    private var detecting = false
    private var detectionMax = detectMeters
    private var lastDetected: Boolean? = null

    init {
        val (rx, ry) = alignment.toRobot(start)
        x = rx
        y = ry
    }

    private val tick = object : Runnable {
        override fun run() {
            step(TICK_MS / 1000.0)
            if (state == RobotLink.State.CONNECTED) main.postDelayed(this, TICK_MS)
        }
    }

    override fun connect() {
        if (state == RobotLink.State.CONNECTED) return
        state = RobotLink.State.CONNECTED
        onState?.invoke(state)
        onEvent?.invoke(RobotEvent.Hello("temi simulator", "sim", 100))
        main.post(tick)
    }

    override fun disconnect() {
        main.removeCallbacks(tick)
        state = RobotLink.State.DISCONNECTED
        onState?.invoke(state)
    }

    override fun send(command: RobotCommand) {
        when (command) {
            is RobotCommand.GoTo -> {
                target = command.x to command.y
                speed = command.speed.coerceIn(0.3, 1.5)
                turnLeft = 0.0
                emit(RobotEvent.GoToStatus(GoStatus.START, ""))
                emit(RobotEvent.GoToStatus(GoStatus.GOING, ""))
            }
            RobotCommand.Stop -> {
                if (target != null) emit(RobotEvent.GoToStatus(GoStatus.ABORT, "Abort by user"))
                target = null
            }
            is RobotCommand.TurnBy -> {
                target = null
                turnLeft = Math.toRadians(command.degrees.toDouble())
            }
            is RobotCommand.Speak -> onSpeak(command.text)
            is RobotCommand.Detection -> {
                detecting = command.on
                detectionMax = command.maxDistance
                lastDetected = null
            }
            is RobotCommand.GoToLocation -> emit(
                RobotEvent.Error("The simulator has no saved locations")
            )
            RobotCommand.ListLocations -> emit(RobotEvent.Locations(emptyList()))
            is RobotCommand.Hello, is RobotCommand.Screen -> Unit
        }
    }

    /** Advances the simulation by [dt] seconds (public for tests). */
    fun step(dt: Double) {
        if (abs(turnLeft) > 1e-3) {
            val d = turnLeft.coerceIn(-TURN_RATE * dt, TURN_RATE * dt)
            yaw = wrap(yaw + d)
            turnLeft -= d
        }
        target?.let { (tx, ty) ->
            val dx = tx - x
            val dy = ty - y
            val left = hypot(dx, dy)
            val want = atan2(dy, dx)
            // Turn toward the target first, like temi does, then drive.
            val err = wrap(want - yaw)
            yaw = wrap(yaw + err.coerceIn(-TURN_RATE * dt, TURN_RATE * dt))
            if (abs(err) < 0.6) {
                val move = minOf(left, speed * dt)
                x += cos(yaw) * move
                y += sin(yaw) * move
            }
            if (left < ARRIVE_M) {
                target = null
                emit(RobotEvent.GoToStatus(GoStatus.COMPLETE, "Complete"))
            }
        }
        emit(RobotEvent.Pose(x, y, yaw))
        if (detecting) {
            val seen = userAt()?.let { p ->
                val (ux, uy) = alignment.toRobot(p)
                val d = hypot(ux - x, uy - y)
                val angle = wrap(atan2(uy - y, ux - x) - yaw)
                if (d <= detectionMax && abs(angle) < FOV_HALF) d to angle else null
            }
            if (seen != null || lastDetected != false) {
                emit(RobotEvent.Detection(seen != null, seen?.first ?: 0.0, seen?.second ?: 0.0))
            }
            lastDetected = seen != null
        }
    }

    private fun emit(event: RobotEvent) {
        onEvent?.invoke(event)
    }

    private fun wrap(a: Double): Double {
        var r = a
        while (r > PI) r -= 2 * PI
        while (r < -PI) r += 2 * PI
        return r
    }

    private companion object {
        const val TICK_MS = 200L
        const val TURN_RATE = 1.6
        const val ARRIVE_M = 0.15
        const val FOV_HALF = 0.7
    }
}
