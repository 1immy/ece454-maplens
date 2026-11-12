package edu.wisc.maplens.temi

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnDetectionDataChangedListener
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.model.DetectionData
import com.robotemi.sdk.navigation.listener.OnCurrentPositionChangedListener
import com.robotemi.sdk.navigation.model.Position
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The MapLens companion on temi. It runs a small server on [PORT] that the MapLens phone app
 * connects to over the local network, and turns each line the phone sends (the JSON protocol
 * in the phone app's `robot/RobotProtocol.kt`) into a temi SDK call: go to a position, stop,
 * turn, speak, Detection Mode, a message and button on temi's screen. It sends back temi's
 * pose, go-to status, who it detects, and screen-button taps. The phone decides where temi
 * goes and when it waits; temi's own navigation drives and avoids obstacles.
 */
class MainActivity :
    Activity(),
    OnRobotReadyListener,
    OnGoToLocationStatusChangedListener,
    OnCurrentPositionChangedListener,
    OnDetectionDataChangedListener {

    private val robot: Robot get() = Robot.getInstance()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile private var client: Socket? = null

    @Volatile private var out: OutputStreamWriter? = null
    private var server: ServerSocket? = null
    private var lastPoseMs = 0L

    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout())
        startServer()
    }

    override fun onStart() {
        super.onStart()
        robot.addOnRobotReadyListener(this)
        robot.addOnGoToLocationStatusChangedListener(this)
        robot.addOnCurrentPositionChangedListener(this)
        robot.addOnDetectionDataChangedListener(this)
    }

    override fun onStop() {
        super.onStop()
        robot.removeOnRobotReadyListener(this)
        robot.removeOnGoToLocationStatusChangedListener(this)
        robot.removeOnCurrentPositionChangedListener(this)
        robot.removeOnDetectionDataChangedListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { server?.close() }
        runCatching { client?.close() }
        io.shutdownNow()
        writer.shutdownNow()
    }

    override fun onRobotReady(isReady: Boolean) {
        if (!isReady) return
        // Keep the app on screen and in charge while guiding.
        runCatching { robot.hideTopBar() }
        runCatching { robot.requestToBeKioskApp() }
        setStatus("Ready on ${address()}:$PORT")
    }

    // ---- Phone link ----

    private fun startServer() {
        io.execute {
            val s = runCatching { ServerSocket(PORT) }.getOrElse {
                main.post { setStatus("Can't listen on $PORT: ${it.message}") }
                return@execute
            }
            server = s
            main.post { setStatus("Waiting for MapLens on ${address()}:$PORT") }
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                // One phone at a time: a new connection replaces the old one.
                runCatching { client?.close() }
                client = socket
                out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                main.post { setStatus("MapLens connected") }
                io.execute { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        runCatching {
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).forEachLine { line ->
                val msg = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                main.post { handle(msg) }
            }
        }
        if (client === socket) {
            main.post {
                // Lost the phone mid-guide: stop rather than walk on alone.
                robot.stopMovement()
                setStatus("MapLens disconnected. Waiting on ${address()}:$PORT")
            }
        }
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("type")) {
            "hello" -> send(
                JSONObject().put("type", "hello").put("model", "temi")
                    .put("sdk", "1.138.0").put("version", 1)
                    .put("battery", robot.batteryData?.level ?: -1),
            )
            "goTo" -> {
                val yaw = if (msg.has("yaw")) msg.getDouble("yaw").toFloat() else NO_ROTATION
                runCatching { robot.setCurrentGoToSpeed(msg.optDouble("speed", 0.6).toFloat()) }
                robot.goToPosition(Position(msg.getDouble("x").toFloat(), msg.getDouble("y").toFloat(), yaw, 0))
            }
            "goToLocation" -> robot.goTo(msg.getString("name"))
            "stop" -> robot.stopMovement()
            "turnBy" -> robot.turnBy(msg.getInt("degrees"), 0.6f)
            "speak" -> robot.speak(TtsRequest.create(msg.getString("text"), false))
            "detection" -> robot.setDetectionModeOn(
                msg.optBoolean("on", true),
                msg.optDouble("maxDistance", 3.0).toFloat(),
            )
            "screen" -> showScreen(msg.optString("message"), msg.optString("button", ""))
            "listLocations" -> send(
                JSONObject().put("type", "locations").put("names", JSONArray(robot.locations)),
            )
        }
    }

    private fun send(json: JSONObject) {
        val w = out ?: return
        val line = json.toString()
        writer.execute {
            runCatching {
                w.write(line)
                w.write("\n")
                w.flush()
            }
        }
    }

    // ---- temi → phone ----

    override fun onCurrentPositionChanged(position: Position) {
        val now = System.currentTimeMillis()
        if (now - lastPoseMs < POSE_PERIOD_MS) return
        lastPoseMs = now
        send(
            JSONObject().put("type", "pose").put("x", position.x.toDouble())
                .put("y", position.y.toDouble()).put("yaw", position.yaw.toDouble()),
        )
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String,
    ) {
        send(JSONObject().put("type", "goToStatus").put("status", status).put("description", description))
    }

    override fun onDetectionDataChanged(detectionData: DetectionData) {
        send(
            JSONObject().put("type", "detection").put("detected", detectionData.isDetected)
                .put("distance", detectionData.distance).put("angle", detectionData.angle),
        )
    }

    // ---- temi's screen ----

    private fun showScreen(text: String, buttonId: String) {
        message.text = text
        button.visibility = if (buttonId.isEmpty()) View.GONE else View.VISIBLE
        button.setOnClickListener {
            button.visibility = View.GONE
            send(JSONObject().put("type", "button").put("id", buttonId))
        }
    }

    private fun layout(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(0, 121, 107))
        setPadding(64, 48, 64, 48)
        title = TextView(context).apply {
            text = "MapLens guide"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        message = TextView(context).apply {
            textSize = 40f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        // Big enough to press from standing height ("Door is open").
        button = Button(context).apply {
            text = "Door is open"
            textSize = 32f
            visibility = View.GONE
        }
        status = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.argb(200, 255, 255, 255))
        }
        addView(title)
        addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 48 })
        addView(button, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 48 })
        addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 64 })
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    /** temi's own IP address on the network, to type into the phone. */
    @Suppress("DEPRECATION")
    private fun address(): String {
        val ip = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress
        return String.format(Locale.US, "%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    private companion object {
        const val PORT = 8765

        /** goToPosition yaw that means "don't rotate on arrival". */
        const val NO_ROTATION = 999f
        const val POSE_PERIOD_MS = 200L
    }
}
