package edu.wisc.maplens

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import edu.wisc.maplens.guidance.FakePoseStream
import edu.wisc.maplens.guidance.Instruction
import edu.wisc.maplens.guidance.Maneuver
import edu.wisc.maplens.guidance.RouteGuide
import edu.wisc.maplens.guidance.headline
import edu.wisc.maplens.live.FacingOption
import edu.wisc.maplens.live.LiveMotionSource
import edu.wisc.maplens.live.LiveStats
import edu.wisc.maplens.live.SessionLog
import edu.wisc.maplens.live.checkpoint
import edu.wisc.maplens.live.corridorIds
import edu.wisc.maplens.live.facingOptions
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.roomDestinations
import edu.wisc.maplens.tracking.ParticleTracker
import edu.wisc.maplens.tracking.TrackingConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The navigation screen: the bundled EH3 map, a room picker, the route, and a turn arrow with
 * a step countdown (#9, #10). Opened from Developer on [HomeActivity] until a photographed and
 * verified map can be navigated (#16).
 *
 * Two pose sources drive it. **Demo** walks the route with a [FakePoseStream]. **Live** asks
 * which corridor the user faces at START (sets θ0), then feeds real steps and heading through
 * [LiveMotionSource] into Johnny's [ParticleTracker] (#50).
 * Live mode also logs every pose and tapped checkpoint to a CSV for the G2 gate.
 *
 * Capture and Verify are skipped: the hand-traced graph goes straight to Calibrate, and k is
 * the demo value in [Demo] until calibration (#12). Each destination starts a fresh
 * [NavigationSession], the only writer of route and position; this Activity renders it.
 */
class MainActivity : Activity() {
    private lateinit var mapView: MapView
    private lateinit var arrow: TextView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var remaining: TextView
    private lateinit var destinationButton: Button
    private lateinit var actionButton: Button
    private lateinit var modeButton: Button

    private val loader = Executors.newSingleThreadExecutor()
    private val ticker = Handler(Looper.getMainLooper())
    private val stride = TrackingConfig().nominalStrideMeters
    private val live by lazy { LiveMotionSource(this, ::onLivePose) }

    /** Writes the session CSV; [log] is only touched on this thread. */
    private val logIo = Executors.newSingleThreadExecutor()
    private var log: SessionLog? = null
    private var logFile: File? = null

    private var graph: FloorGraph? = null
    private var corridors: Map<String, String> = emptyMap()
    private var room: String? = null
    private var session: NavigationSession? = null
    private var guide: RouteGuide? = null
    private var walk: FakePoseStream? = null
    private var walking = false
    private var liveMode = false

    /** Live tracker is being fed steps; cleared on pause, arrival or a new destination. */
    private var following = false
    private var resumeFollowing = false
    private var stats: LiveStats? = null

    /** Calibration already logged and announced for this navigation, by its timestamp. */
    private var announcedCalibrationNs: Long? = null
    private var checkpoints = 0

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
                    corridors = g.corridorIds()
                    mapView.bitmap = image
                    mapView.start = g.nodes.getValue(g.startNodeId).position
                    destinationButton.isEnabled = true
                    showMessage(getString(R.string.pick_destination), getString(R.string.at_start))
                }.onFailure { showMessage(getString(R.string.map_failed), it.message.orEmpty()) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!liveMode) return
        live.start()
        if (resumeFollowing) {
            resumeFollowing = false
            askFacing(getString(R.string.facing_again))
        }
    }

    override fun onPause() {
        super.onPause()
        setWalking(false)
        // Sensors stop with the screen; the tracker can't know where the user went meanwhile,
        // so live tracking re-anchors at START on return instead of carrying on silently.
        resumeFollowing = following
        following = false
        live.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
        logIo.execute { log?.close() }
        logIo.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STEP_PERMISSION) return
        if (!live.hasStepPermission) toast(getString(R.string.using_peaks))
        setLiveMode(true)
    }

    private fun toggleMode() {
        if (liveMode) return setLiveMode(false)
        if (!live.hasHeadingSensor) return toast(getString(R.string.no_heading_sensor))
        if (Build.VERSION.SDK_INT >= 29 && !live.hasStepPermission) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), STEP_PERMISSION)
            return
        }
        setLiveMode(true)
    }

    private fun setLiveMode(on: Boolean) {
        setWalking(false)
        following = false
        liveMode = on
        if (on) live.start() else live.stop()
        modeButton.text = getString(if (on) R.string.mode_live else R.string.mode_demo)
        modeButton.contentDescription = getString(
            R.string.mode_description,
            modeButton.text,
            getString(if (on) R.string.mode_demo else R.string.mode_live),
        )
        actionButton.text = getString(if (on) R.string.checkpoint else R.string.walk)
        actionButton.isEnabled = false
        val g = graph
        val r = room
        if (g != null && r != null) navigateTo(g, r)
    }

    private fun pickDestination() {
        val g = graph ?: return
        val rooms = g.roomDestinations()
        AlertDialog.Builder(this)
            .setTitle(R.string.where_to)
            .setItems(rooms.toTypedArray()) { _, i -> navigateTo(g, rooms[i]) }
            .show()
    }

    private fun navigateTo(g: FloorGraph, destination: String) {
        setWalking(false)
        following = false
        live.pause()
        val next = NavigationSession(AStarRouter())
        next.onMapCaptured(g)
        next.onGraphVerified(g)
        if (!next.onCalibratedToRoom(Demo.STEP_LENGTH_PX, destination)) {
            toast(getString(R.string.no_route, destination))
            return
        }
        val routeGuide = RouteGuide(checkNotNull(next.graph), checkNotNull(next.route))
        session = next
        guide = routeGuide
        room = destination
        stats = null
        announcedCalibrationNs = null
        checkpoints = 0
        mapView.checkpoints = emptyList()
        mapView.route = routeGuide.path.points
        destinationButton.text = getString(R.string.destination, destination)
        if (liveMode) {
            walk = null
            mapView.pose = null
            actionButton.isEnabled = false
            askFacing(getString(R.string.facing))
        } else {
            val stream = FakePoseStream(routeGuide.path, Demo.STEP_LENGTH_PX)
            walk = stream
            openLog("demo", destination, routeGuide.path.bearingAt(0.0))
            actionButton.isEnabled = true
            actionButton.text = getString(R.string.walk)
            onPose(stream.current())
        }
    }

    /** START: "which way are you facing?" Each answer is a corridor; its bearing sets θ0. */
    private fun askFacing(title: String) {
        val g = graph ?: return
        val options = g.facingOptions(g.startNodeId)
        showMessage(title, getString(R.string.facing_hint))
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.map { "${it.arrow}   ${it.description}" }.toTypedArray()) { _, i ->
                startLive(g, options[i])
            }
            .setCancelable(false)
            .show()
    }

    private fun startLive(g: FloorGraph, facing: FacingOption) {
        val destination = room ?: return
        val k = Demo.STEP_LENGTH_PX
        live.begin(
            facing.bearingRad,
            ParticleTracker(),
            setup = { it.start(g, g.startNodeId, facing.bearingRad, k) },
        ) { aligned ->
            if (!aligned) {
                toast(getString(R.string.hold_flat))
                askFacing(getString(R.string.facing))
                return@begin
            }
            following = true
            openLog("live", destination, facing.bearingRad)
            actionButton.isEnabled = true
            onPose(
                PoseEstimate(
                    timestampNanos = SystemClock.elapsedRealtimeNanos(),
                    position = g.nodes.getValue(g.startNodeId).position,
                    headingRad = facing.bearingRad,
                    edgeId = facing.edgeId,
                    spreadPx = 0.0,
                    topClusterWeight = 1.0,
                    stepLengthPx = k,
                ),
            )
        }
    }

    private fun onLivePose(pose: PoseEstimate, latest: LiveStats) {
        if (!following) return
        stats = latest
        val calibratedAt = latest.calibratedAtNanos
        val k = latest.calibratedStepLengthPx
        if (calibratedAt != null && k != null && calibratedAt != announcedCalibrationNs) {
            // Never a silent change: the new k is logged and shown once.
            announcedCalibrationNs = calibratedAt
            logIo.execute { log?.recalibrate(calibratedAt, k) }
            toast(getString(R.string.recalibrated, k))
        }
        onPose(pose)
    }

    /** One pose update: session first (the single writer), then everything that renders it. */
    private fun onPose(pose: PoseEstimate) {
        val s = session ?: return
        val g = guide ?: return
        s.onPose(pose)
        mapView.pose = pose
        logIo.execute { log?.pose(pose) }
        val instruction = g.instruction(pose)
        render(instruction, g.remainingSteps(pose), pose.stepLengthPx)
        if (instruction.maneuver == Maneuver.ARRIVE && instruction.stepsUntil == 0 &&
            s.phase != Phase.ARRIVED
        ) {
            s.onArrived()
            setWalking(false)
            following = false
            live.pause()
            actionButton.isEnabled = false
            logIo.execute { log?.arrive(pose) }
            logFile?.let { toast(getString(R.string.log_saved, it.name)) }
        }
    }

    /** Ground truth for the G2 gate: the user taps where they really are on the map. */
    private fun startCheckpoint() {
        toast(getString(R.string.tap_position))
        mapView.onMapTap = { truth ->
            mapView.onMapTap = null
            recordCheckpoint(truth)
        }
    }

    private fun recordCheckpoint(truth: PixelPoint) {
        val g = graph ?: return
        val pose = session?.pose ?: return
        checkpoints += 1
        val result = g.checkpoint("C$checkpoints", pose, truth, stride, corridors)
        mapView.checkpoints = mapView.checkpoints + truth
        val now = SystemClock.elapsedRealtimeNanos()
        logIo.execute { log?.checkpoint(now, result) }
        val verdict = getString(
            if (result.sameCorridor) R.string.same_corridor else R.string.other_corridor,
        )
        toast(getString(R.string.checkpoint_result, result.label, result.errorMeters, verdict))
    }

    private fun openLog(mode: String, destination: String, facingRad: Double) {
        val dir = getExternalFilesDir("sessions") ?: File(filesDir, "sessions")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "session-$stamp-$mode-$destination.csv")
        logFile = file
        val now = SystemClock.elapsedRealtimeNanos()
        logIo.execute {
            log?.close()
            log = runCatching {
                dir.mkdirs()
                SessionLog(file.bufferedWriter()).apply {
                    start(now, mode, destination, facingRad, Demo.STEP_LENGTH_PX)
                }
            }.getOrNull()
        }
    }

    /** [k] is the tracker's current step length, so the banner shows calibration as it happens. */
    private fun render(instruction: Instruction, stepsLeft: Int, k: Double) {
        val heading = instruction.maneuver
        val straightOn = heading == Maneuver.ARRIVE && instruction.stepsUntil > 0
        arrow.text = ARROWS.getValue(if (straightOn) Maneuver.STRAIGHT else heading)
        arrow.visibility = View.VISIBLE
        headline.text = instruction.headline()
        detail.text = instruction.detail
        val meters = (stepsLeft * stride).roundToInt()
        val sensors = stats
        remaining.text = when {
            stepsLeft <= 0 -> getString(R.string.arrived_phase, session?.phase?.name.orEmpty())
            liveMode && sensors != null -> getString(
                R.string.remaining_live,
                stepsLeft,
                meters,
                sensors.steps,
                sensors.skipped,
                getString(
                    if (sensors.usingPeaks) R.string.source_peaks else R.string.source_detector,
                ),
                k,
            )
            else -> getString(R.string.remaining, stepsLeft, meters, k)
        }
    }

    private fun setWalking(on: Boolean) {
        walking = on && walk?.finished == false
        ticker.removeCallbacks(tick)
        if (walking) ticker.postDelayed(tick, Demo.STEP_INTERVAL_MS)
        if (::actionButton.isInitialized && !liveMode) {
            actionButton.text = getString(if (walking) R.string.pause else R.string.walk)
        }
    }

    private fun onAction() = if (liveMode) startCheckpoint() else setWalking(!walking)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun showMessage(title: String, body: String) {
        arrow.text = ""
        arrow.visibility = View.GONE
        headline.text = title
        detail.text = body
        remaining.text = ""
    }

    private fun buildLayout(): View {
        fun light(sizeSp: Float, alpha: Int = 255, bold: Boolean = false) =
            text(sizeSp, Color.argb(alpha, 255, 255, 255), bold)
        arrow = light(40f, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(28, 255, 255, 255), radiusDp = 16)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        headline = light(Ui.HEADING, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        detail = light(Ui.BODY, alpha = 230)
        remaining = light(Ui.CAPTION, alpha = 200)
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(headline)
            addView(detail, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            addView(remaining, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        modeButton = styledButton(ButtonStyle.DARK, getString(R.string.mode_demo)).apply {
            minWidth = dp(88)
            contentDescription = getString(
                R.string.mode_description,
                getString(R.string.mode_demo),
                getString(R.string.mode_live),
            )
            setOnClickListener { toggleMode() }
        }
        val back = ImageButton(this).apply {
            setImageDrawable(icon(R.drawable.ic_arrow_back, Color.WHITE))
            background = pressable(
                rounded(Color.TRANSPARENT, radiusDp = 24),
                Color.argb(56, 255, 255, 255),
            )
            contentDescription = getString(R.string.nav_back)
            setOnClickListener { finish() }
        }
        val topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(Ui.TOUCH_DP), dp(Ui.TOUCH_DP)))
            addView(
                light(Ui.BODY, alpha = 230, bold = true).apply {
                    setText(R.string.nav_title)
                    markHeading()
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(4) },
            )
            addView(modeButton, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val guidance = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(arrow, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(
                words,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) },
            )
        }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.INK)
            setPadding(dp(8), dp(4), dp(16), dp(20))
            addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(
                guidance,
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(12)
                    marginStart = dp(8)
                },
            )
            padForSystemBars(bottom = false)
        }
        mapView = MapView(this).apply { setBackgroundColor(Ui.BACKGROUND) }
        destinationButton = styledButton(
            ButtonStyle.SECONDARY,
            getString(R.string.choose_destination),
        ).apply {
            isEnabled = false
            setOnClickListener { pickDestination() }
        }
        actionButton = styledButton(ButtonStyle.PRIMARY, getString(R.string.walk)).apply {
            isEnabled = false
            setOnClickListener { onAction() }
        }
        val controls = LinearLayout(this).apply {
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(16), dp(12), dp(16), dp(16))
            addView(destinationButton, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(
                actionButton,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) },
            )
            padForSystemBars(top = false)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(mapView, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(
                View(context).apply { setBackgroundColor(Ui.BORDER) },
                LinearLayout.LayoutParams(MATCH, dp(1)),
            )
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val STEP_PERMISSION = 7

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
