package edu.wisc.maplens

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import edu.wisc.maplens.guidance.FakePoseStream
import edu.wisc.maplens.guidance.Instruction
import edu.wisc.maplens.guidance.Maneuver
import edu.wisc.maplens.guidance.RouteGuide
import edu.wisc.maplens.guidance.headline
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.roomDestinations
import edu.wisc.maplens.tracking.TrackingConfig
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Guidance UI v1 (#9, #10): the bundled EH3 map, a room picker, the route, and a turn arrow
 * with a step countdown. Until the tracker exists, a [FakePoseStream] walks the route.
 *
 * Capture and Verify are skipped: the hand-traced graph goes straight to Calibrate, and k is
 * the demo value in [Demo]. Each destination starts a fresh [NavigationSession], which stays
 * the only writer of route and position; this Activity only renders what it holds.
 */
class MainActivity : Activity() {
    private lateinit var mapView: MapView
    private lateinit var arrow: TextView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var remaining: TextView
    private lateinit var destinationButton: Button
    private lateinit var walkButton: Button

    private val loader = Executors.newSingleThreadExecutor()
    private val ticker = Handler(Looper.getMainLooper())
    private val stride = TrackingConfig().nominalStrideMeters

    private var graph: FloorGraph? = null
    private var session: NavigationSession? = null
    private var guide: RouteGuide? = null
    private var walk: FakePoseStream? = null
    private var walking = false

    private val tick = object : Runnable {
        override fun run() {
            val stream = walk ?: return
            onPose(stream.step())
            if (walking && !stream.finished) ticker.postDelayed(this, Demo.STEP_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        showMessage(getString(R.string.loading_map), "")
        loader.execute {
            val loaded = runCatching {
                MapAssets.loadGraph(this) to MapAssets.loadImage(this)
            }
            runOnUiThread {
                loaded.onSuccess { (g, image) ->
                    graph = g
                    mapView.bitmap = image
                    mapView.start = g.nodes.getValue(g.startNodeId).position
                    destinationButton.isEnabled = true
                    showMessage(getString(R.string.pick_destination), getString(R.string.at_start))
                }.onFailure { showMessage(getString(R.string.map_failed), it.message.orEmpty()) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        setWalking(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun pickDestination() {
        val g = graph ?: return
        val rooms = g.roomDestinations()
        AlertDialog.Builder(this)
            .setTitle(R.string.where_to)
            .setItems(rooms.toTypedArray()) { _, i -> navigateTo(g, rooms[i]) }
            .show()
    }

    private fun navigateTo(g: FloorGraph, room: String) {
        setWalking(false)
        val next = NavigationSession(AStarRouter())
        next.onMapCaptured(g)
        next.onGraphVerified(g)
        if (!next.onCalibratedToRoom(Demo.STEP_LENGTH_PX, room)) {
            Toast.makeText(this, getString(R.string.no_route, room), Toast.LENGTH_SHORT).show()
            return
        }
        val routeGuide = RouteGuide(checkNotNull(next.graph), checkNotNull(next.route))
        session = next
        guide = routeGuide
        walk = FakePoseStream(routeGuide.path, Demo.STEP_LENGTH_PX)
        mapView.route = routeGuide.path.points
        destinationButton.text = getString(R.string.destination, room)
        walkButton.isEnabled = true
        walkButton.text = getString(R.string.walk)
        onPose(checkNotNull(walk).current())
    }

    /** One pose update: session first (the single writer), then everything that renders it. */
    private fun onPose(pose: PoseEstimate) {
        val s = session ?: return
        val g = guide ?: return
        s.onPose(pose)
        mapView.pose = pose
        val instruction = g.instruction(pose)
        render(instruction, g.remainingSteps(pose))
        if (instruction.maneuver == Maneuver.ARRIVE && instruction.stepsUntil == 0) {
            s.onArrived()
            setWalking(false)
            walkButton.isEnabled = false
        }
    }

    private fun render(instruction: Instruction, stepsLeft: Int) {
        val heading = instruction.maneuver
        val straightOn = heading == Maneuver.ARRIVE && instruction.stepsUntil > 0
        arrow.text = ARROWS.getValue(if (straightOn) Maneuver.STRAIGHT else heading)
        headline.text = instruction.headline()
        detail.text = instruction.detail
        val meters = (stepsLeft * stride).roundToInt()
        remaining.text = if (stepsLeft > 0) {
            getString(R.string.remaining, stepsLeft, meters, Demo.STEP_LENGTH_PX.roundToInt())
        } else {
            getString(R.string.arrived_phase, session?.phase?.name.orEmpty())
        }
    }

    private fun setWalking(on: Boolean) {
        walking = on && walk?.finished == false
        ticker.removeCallbacks(tick)
        if (walking) ticker.postDelayed(tick, Demo.STEP_INTERVAL_MS)
        if (::walkButton.isInitialized) {
            walkButton.text = getString(if (walking) R.string.pause else R.string.walk)
        }
    }

    private fun showMessage(title: String, body: String) {
        arrow.text = ""
        headline.text = title
        detail.text = body
        remaining.text = ""
    }

    private fun buildLayout(): View {
        val pad = (16 * resources.displayMetrics.density).roundToInt()
        fun text(sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = sizeSp
            setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        arrow = text(48f, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = pad * 4
        }
        headline = text(22f, bold = true)
        detail = text(16f)
        remaining = text(14f)
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, 0, 0)
            addView(headline)
            addView(detail)
            addView(remaining)
        }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(24, 44, 42))
            setPadding(pad, pad * 2, pad, pad)
            addView(arrow)
            addView(words)
        }
        mapView = MapView(this)
        destinationButton = Button(this).apply {
            text = getString(R.string.choose_destination)
            isEnabled = false
            setOnClickListener { pickDestination() }
        }
        walkButton = Button(this).apply {
            text = getString(R.string.walk)
            isEnabled = false
            setOnClickListener { setWalking(!walking) }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad / 2, pad / 2, pad / 2, pad)
            addView(destinationButton, LinearLayout.LayoutParams(0, WRAP, 2f))
            addView(walkButton, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(mapView, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        val ARROWS = mapOf(
            Maneuver.STRAIGHT to "↑",
            Maneuver.TURN_LEFT to "←",
            Maneuver.TURN_RIGHT to "→",
            Maneuver.SLIGHT_LEFT to "↖",
            Maneuver.SLIGHT_RIGHT to "↗",
            Maneuver.U_TURN to "↶",
            Maneuver.ARRIVE to "●",
        )
    }
}
