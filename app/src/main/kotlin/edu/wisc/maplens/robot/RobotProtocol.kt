package edu.wisc.maplens.robot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the phone asks temi to do. Positions are in temi's own map frame (metres, yaw in
 * radians), the frame its SDK's `goToPosition` and `Position` use; [MapAlignment] converts
 * from MapLens map pixels.
 */
sealed interface RobotCommand {
    data class Hello(val client: String) : RobotCommand

    /** `goToPosition`; [yaw] null keeps temi from turning on arrival (SDK yaw 999). */
    data class GoTo(
        val x: Double,
        val y: Double,
        val yaw: Double?,
        val speed: Double
    ) : RobotCommand

    /** `goTo` a saved location by name. */
    data class GoToLocation(val name: String, val speed: Double) : RobotCommand

    data object Stop : RobotCommand

    /** `turnBy`: positive turns left (counter-clockwise), as in the temi SDK. */
    data class TurnBy(val degrees: Int) : RobotCommand

    data class Speak(val text: String) : RobotCommand

    /** Detection Mode: report a person in front within [maxDistance] metres. */
    data class Detection(val on: Boolean, val maxDistance: Double) : RobotCommand

    /** Show a message and an optional button on temi's screen (e.g. "Door is open"). */
    data class Screen(val message: String, val button: String?) : RobotCommand

    data object ListLocations : RobotCommand
}

/** temi's go-to states, as the SDK reports them. */
enum class GoStatus { START, CALCULATING, GOING, COMPLETE, ABORT, REPOSING }

/** What temi reports back. */
sealed interface RobotEvent {
    data class Hello(val model: String, val sdk: String, val battery: Int?) : RobotEvent

    /** Current pose in temi's map frame (metres, radians). */
    data class Pose(val x: Double, val y: Double, val yaw: Double) : RobotEvent

    data class GoToStatus(val status: GoStatus, val description: String) : RobotEvent

    /** Detection Mode: someone in front, [distance] metres away, [angle] off centre. */
    data class Detection(val detected: Boolean, val distance: Double, val angle: Double) :
        RobotEvent

    data class Locations(val names: List<String>) : RobotEvent

    /** The button shown with [RobotCommand.Screen] was pressed on temi's screen. */
    data class Button(val id: String) : RobotEvent

    data class Error(val message: String) : RobotEvent
}

/**
 * One JSON object per line, in both directions, over a plain socket on the local network
 * (the phone's hotspot or the lab Wi-Fi; no internet). Field names are short and stable so
 * the temi companion app (see `docs/temi-integration.md`) can be written against this file.
 * Unknown types and fields are ignored, so either side can be upgraded first.
 */
object RobotProtocol {
    /** Bump when a message changes meaning; both sides send it in `hello`. */
    const val VERSION = 1

    const val DEFAULT_PORT = 8765

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(command: RobotCommand): String = buildJsonObject {
        when (command) {
            is RobotCommand.Hello -> {
                put("type", "hello")
                put("client", command.client)
                put("version", VERSION)
            }
            is RobotCommand.GoTo -> {
                put("type", "goTo")
                put("x", command.x)
                put("y", command.y)
                command.yaw?.let { put("yaw", it) }
                put("speed", command.speed)
            }
            is RobotCommand.GoToLocation -> {
                put("type", "goToLocation")
                put("name", command.name)
                put("speed", command.speed)
            }
            RobotCommand.Stop -> put("type", "stop")
            is RobotCommand.TurnBy -> {
                put("type", "turnBy")
                put("degrees", command.degrees)
            }
            is RobotCommand.Speak -> {
                put("type", "speak")
                put("text", command.text)
            }
            is RobotCommand.Detection -> {
                put("type", "detection")
                put("on", command.on)
                put("maxDistance", command.maxDistance)
            }
            is RobotCommand.Screen -> {
                put("type", "screen")
                put("message", command.message)
                command.button?.let { put("button", it) }
            }
            RobotCommand.ListLocations -> put("type", "listLocations")
        }
    }.toString()

    fun encode(event: RobotEvent): String = buildJsonObject {
        when (event) {
            is RobotEvent.Hello -> {
                put("type", "hello")
                put("model", event.model)
                put("sdk", event.sdk)
                put("version", VERSION)
                event.battery?.let { put("battery", it) }
            }
            is RobotEvent.Pose -> {
                put("type", "pose")
                put("x", event.x)
                put("y", event.y)
                put("yaw", event.yaw)
            }
            is RobotEvent.GoToStatus -> {
                put("type", "goToStatus")
                put("status", event.status.name.lowercase())
                put("description", event.description)
            }
            is RobotEvent.Detection -> {
                put("type", "detection")
                put("detected", event.detected)
                put("distance", event.distance)
                put("angle", event.angle)
            }
            is RobotEvent.Locations -> {
                put("type", "locations")
                putJsonArray("names") { event.names.forEach { add(JsonPrimitive(it)) } }
            }
            is RobotEvent.Button -> {
                put("type", "button")
                put("id", event.id)
            }
            is RobotEvent.Error -> {
                put("type", "error")
                put("message", event.message)
            }
        }
    }.toString()

    /** One line from temi, or null if it isn't a message this version understands. */
    fun decodeEvent(line: String): RobotEvent? {
        val o = parse(line) ?: return null
        return when (o.string("type")) {
            "hello" -> RobotEvent.Hello(
                o.string("model") ?: "temi",
                o.string("sdk") ?: "?",
                o.int("battery")
            )
            "pose" -> RobotEvent.Pose(
                o.num("x") ?: return null,
                o.num("y") ?: return null,
                o.num("yaw") ?: 0.0
            )
            "goToStatus" -> {
                val status = o.string("status")?.uppercase()
                    ?.let { s -> GoStatus.entries.firstOrNull { it.name == s } } ?: return null
                RobotEvent.GoToStatus(status, o.string("description").orEmpty())
            }
            "detection" -> RobotEvent.Detection(
                o.bool("detected") ?: false,
                o.num("distance") ?: 0.0,
                o.num("angle") ?: 0.0,
            )
            "locations" -> RobotEvent.Locations(
                o["names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty(),
            )
            "button" -> RobotEvent.Button(o.string("id") ?: return null)
            "error" -> RobotEvent.Error(o.string("message").orEmpty())
            else -> null
        }
    }

    /** One line from the phone, or null (the simulator and the companion app use this). */
    fun decodeCommand(line: String): RobotCommand? {
        val o = parse(line) ?: return null
        return when (o.string("type")) {
            "hello" -> RobotCommand.Hello(o.string("client").orEmpty())
            "goTo" -> RobotCommand.GoTo(
                o.num("x") ?: return null,
                o.num("y") ?: return null,
                o.num("yaw"),
                o.num("speed") ?: DEFAULT_SPEED,
            )
            "goToLocation" -> RobotCommand.GoToLocation(
                o.string("name") ?: return null,
                o.num("speed") ?: DEFAULT_SPEED
            )
            "stop" -> RobotCommand.Stop
            "turnBy" -> RobotCommand.TurnBy(o.int("degrees") ?: return null)
            "speak" -> RobotCommand.Speak(o.string("text") ?: return null)
            "detection" -> RobotCommand.Detection(o.bool("on") ?: true, o.num("maxDistance") ?: 3.0)
            "screen" -> RobotCommand.Screen(o.string("message").orEmpty(), o.string("button"))
            "listLocations" -> RobotCommand.ListLocations
            else -> null
        }
    }

    /** temi's slowest comfortable walking-guide speed, m/s (the SDK accepts 0.3 to 1.5). */
    const val DEFAULT_SPEED = 0.6

    private fun parse(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String) =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
