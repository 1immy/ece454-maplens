package edu.wisc.maplens.robot

import android.content.Context
import edu.wisc.maplens.model.PixelPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Everything the phone remembers about the guide robot setup, saved on the phone. */
data class TemiSetup(
    val simulator: Boolean = true,
    val host: String = "192.168.43.1",
    val port: Int = RobotProtocol.DEFAULT_PORT,
    /** Places known in both maps, for [MapAlignment]. */
    val anchors: List<Anchor> = emptyList(),
    /** Graph nodes where a door crosses the corridor: temi stops and asks for help there. */
    val doorNodes: Set<String> = emptySet(),
    val leadMeters: Double = EscortParams().leadMeters,
    val speed: Double = EscortParams().speed,
)

/**
 * The one guide-robot connection the app has, shared by the temi dev screen (which sets it
 * up) and navigation (which uses it), plus the latest telemetry and the saved [TemiSetup].
 * Main thread only.
 */
object RobotHub {
    var setup = TemiSetup()
        private set

    var link: RobotLink? = null
        private set

    val connected: Boolean get() = link?.state == RobotLink.State.CONNECTED

    /** Latest pose, status and detection temi reported. */
    var pose: RobotEvent.Pose? = null
        private set
    var hello: RobotEvent.Hello? = null
        private set
    var lastError: String? = null
        private set

    /** Where the simulator should look for the walker (navigation sets this). */
    var userAt: () -> PixelPoint? = { null }

    /** What a simulated temi says, shown on the phone instead of spoken by a robot. */
    var onSimulatedSpeech: (String) -> Unit = {}

    private val eventListeners = mutableListOf<(RobotEvent) -> Unit>()
    private val stateListeners = mutableListOf<(RobotLink.State) -> Unit>()

    fun addListener(onEvent: (RobotEvent) -> Unit, onState: (RobotLink.State) -> Unit = {}) {
        eventListeners += onEvent
        stateListeners += onState
    }

    fun removeListener(onEvent: (RobotEvent) -> Unit, onState: (RobotLink.State) -> Unit = {}) {
        eventListeners -= onEvent
        stateListeners -= onState
    }

    /**
     * Connects to temi (or starts the simulator at [start], map px) with the current setup.
     * [pxPerMeter] sizes the simulator's made-up map.
     */
    fun connect(start: PixelPoint, pxPerMeter: Double) {
        disconnect()
        val next: RobotLink = if (setup.simulator) {
            SimulatedTemi(
                MapAlignment.simulated(pxPerMeter),
                start,
                userAt = { userAt() },
                onSpeak = { onSimulatedSpeech(it) },
            )
        } else {
            TcpRobotLink(setup.host, setup.port)
        }
        next.onEvent = { e ->
            when (e) {
                is RobotEvent.Pose -> pose = e
                is RobotEvent.Hello -> hello = e
                is RobotEvent.Error -> lastError = e.message
                else -> Unit
            }
            eventListeners.toList().forEach { it(e) }
        }
        next.onState = { s -> stateListeners.toList().forEach { it(s) } }
        link = next
        pose = null
        hello = null
        lastError = null
        next.connect()
    }

    fun disconnect() {
        link?.let {
            it.send(RobotCommand.Stop)
            it.disconnect()
        }
        link = null
    }

    fun send(command: RobotCommand) {
        link?.send(command)
    }

    /** How temi's frame maps onto the MapLens map: made up for the simulator, else fitted. */
    fun alignment(pxPerMeter: Double): MapAlignment? =
        if (setup.simulator) MapAlignment.simulated(pxPerMeter) else MapAlignment.fit(setup.anchors)

    fun update(context: Context, change: (TemiSetup) -> TemiSetup) {
        setup = change(setup)
        prefs(context).edit().putString(KEY, encode(setup)).apply()
    }

    fun load(context: Context) {
        prefs(context).getString(KEY, null)?.let { text ->
            runCatching { decode(text) }.onSuccess { setup = it }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("temi", Context.MODE_PRIVATE)

    internal fun encode(s: TemiSetup): String = buildJsonObject {
        put("simulator", s.simulator)
        put("host", s.host)
        put("port", s.port)
        put("leadMeters", s.leadMeters)
        put("speed", s.speed)
        put("doorNodes", buildJsonArray { s.doorNodes.sorted().forEach { add(JsonPrimitive(it)) } })
        put(
            "anchors",
            buildJsonArray {
                s.anchors.forEach { a ->
                    add(
                        buildJsonObject {
                            put("label", a.label)
                            put("mapX", a.map.x)
                            put("mapY", a.map.y)
                            put("robotX", a.robotX)
                            put("robotY", a.robotY)
                        },
                    )
                }
            },
        )
    }.toString()

    internal fun decode(text: String): TemiSetup {
        val o = Json.parseToJsonElement(text).jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.content
        fun num(k: String) = o[k]?.jsonPrimitive?.double
        val defaults = TemiSetup()
        return TemiSetup(
            simulator = str("simulator")?.toBooleanStrictOrNull() ?: defaults.simulator,
            host = str("host") ?: defaults.host,
            port = str("port")?.toIntOrNull() ?: defaults.port,
            anchors = (o["anchors"] as? JsonArray)?.map { e ->
                val a = e.jsonObject
                Anchor(
                    a["label"]!!.jsonPrimitive.content,
                    PixelPoint(a["mapX"]!!.jsonPrimitive.double, a["mapY"]!!.jsonPrimitive.double),
                    a["robotX"]!!.jsonPrimitive.double,
                    a["robotY"]!!.jsonPrimitive.double,
                )
            }.orEmpty(),
            doorNodes = o["doorNodes"]?.jsonArray
                ?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
            leadMeters = num("leadMeters") ?: defaults.leadMeters,
            speed = num("speed") ?: defaults.speed,
        )
    }

    private const val KEY = "setup"
}
