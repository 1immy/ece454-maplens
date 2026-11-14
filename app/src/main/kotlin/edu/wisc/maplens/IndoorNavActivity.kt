package edu.wisc.maplens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import edu.wisc.maplens.guidance.Announcement
import edu.wisc.maplens.guidance.CueAnnouncer
import edu.wisc.maplens.guidance.Haptic
import edu.wisc.maplens.guidance.Instruction
import edu.wisc.maplens.guidance.Maneuver
import edu.wisc.maplens.guidance.RerouteParams
import edu.wisc.maplens.guidance.RerouteReason
import edu.wisc.maplens.guidance.RouteGuide
import edu.wisc.maplens.guidance.RouteProgress
import edu.wisc.maplens.guidance.RouteStatus
import edu.wisc.maplens.guidance.VoiceAndHaptics
import edu.wisc.maplens.live.FacingOption
import edu.wisc.maplens.live.LiveMotionSource
import edu.wisc.maplens.live.LiveStats
import edu.wisc.maplens.live.SessionLog
import edu.wisc.maplens.live.checkpoint
import edu.wisc.maplens.live.corridorIds
import edu.wisc.maplens.live.facingOptions
import edu.wisc.maplens.model.ConfidenceCheck
import edu.wisc.maplens.model.ConfidenceLevel
import edu.wisc.maplens.model.ConfidenceState
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.RecoveryAction
import edu.wisc.maplens.model.edgePoints
import edu.wisc.maplens.nav.CompassView
import edu.wisc.maplens.nav.DemoScenario
import edu.wisc.maplens.nav.DemoWalker
import edu.wisc.maplens.nav.IndoorMapView
import edu.wisc.maplens.nav.LabelBox
import edu.wisc.maplens.nav.ManeuverView
import edu.wisc.maplens.nav.MapRooms
import edu.wisc.maplens.nav.NavMath
import edu.wisc.maplens.nav.Place
import edu.wisc.maplens.nav.PlaceKind
import edu.wisc.maplens.nav.PlaceMatch
import edu.wisc.maplens.nav.PlaceSearch
import edu.wisc.maplens.nav.PuckMotion
import edu.wisc.maplens.nav.RoomFinder
import edu.wisc.maplens.nav.SignDetection
import edu.wisc.maplens.nav.SignReader
import edu.wisc.maplens.nav.SignScanView
import edu.wisc.maplens.nav.SignVoter
import edu.wisc.maplens.nav.Trail
import edu.wisc.maplens.nav.nearestRoomLabel
import edu.wisc.maplens.nav.places
import edu.wisc.maplens.nav.rerouteTo
import edu.wisc.maplens.nav.smoothedCorridors
import edu.wisc.maplens.robot.Carry
import edu.wisc.maplens.robot.EscortCue
import edu.wisc.maplens.robot.EscortState
import edu.wisc.maplens.robot.RobotHub
import edu.wisc.maplens.robot.TemiGuide
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.Reroute
import edu.wisc.maplens.routing.routeToLabel
import edu.wisc.maplens.tracking.FourCheckConfidenceMonitor
import edu.wisc.maplens.tracking.ParticleTracker
import edu.wisc.maplens.tracking.SignOutcome
import edu.wisc.maplens.tracking.SignResult
import edu.wisc.maplens.tracking.TrackingConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The app's navigation screen, laid out like a phone maps app: search a room, see it pinned,
 * preview the walking route, then follow turn-by-turn guidance with a heading-up, tilted map
 * that follows a blue position arrow. It opens a saved, verified map ([open] with a map id) or,
 * without one, the bundled EH3 map (Developer).
 *
 * A fresh [NavigationSession] per destination is the only writer of route and position,
 * [RouteGuide] turns poses into instructions, **Demo** walks the route with a
 * [DemoWalker] (on the route, or a wrong turn or wrong way to show rerouting), and **Live** anchors heading at START from the corridor the user faces,
 * then feeds steps and heading through [LiveMotionSource] into [ParticleTracker]. Live also
 * streams the aligned heading between steps, so the arrow turns with the user in real time
 * without GPS or Wi-Fi.
 * Live sessions keep the CSV log and tap-to-mark checkpoints of the G2 gate.
 */
class IndoorNavActivity : ComponentActivity() {
    private enum class Screen {
        BROWSE,
        SEARCH,
        PLACE,
        PREVIEW,
        FACING,
        NAVIGATE,
        CHECKPOINT,
        ARRIVED,
    }

    private lateinit var root: FrameLayout
    private lateinit var map: IndoorMapView
    private lateinit var topArea: LinearLayout
    private lateinit var searchBar: LinearLayout

    /** The search bar's text: the hint, or the open place's name, as maps apps do. */
    private lateinit var searchLabel: TextView
    private lateinit var modeChip: TextView
    private lateinit var chips: HorizontalScrollView

    /** Exit, elevator and AED shortcuts; each shows only when the map has one to walk to. */
    private val featureChips = mutableMapOf<PlaceKind, View>()
    private lateinit var routeCard: LinearLayout
    private lateinit var routeTo: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerCard: LinearLayout
    private lateinit var bannerIcon: ManeuverView
    private lateinit var bannerSpinner: ProgressBar
    private lateinit var bannerDistance: TextView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerDetail: TextView
    private lateinit var thenStrip: LinearLayout
    private lateinit var thenIcon: ManeuverView
    private lateinit var hintCard: LinearLayout
    private lateinit var confidenceChip: LinearLayout
    private lateinit var confidenceDot: View
    private lateinit var confidenceTitle: TextView
    private lateinit var confidenceDetail: TextView
    private lateinit var confidenceAction: TextView
    private lateinit var hintTitle: TextView
    private lateinit var hintBody: TextView
    private lateinit var compass: FrameLayout
    private lateinit var compassNeedle: CompassView
    private lateinit var bottomArea: LinearLayout
    private lateinit var recenter: TextView
    private lateinit var locate: ImageButton
    private lateinit var snack: TextView
    private lateinit var sheet: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchClear: ImageButton
    private lateinit var results: LinearLayout

    // Navigation sheet parts, updated on every pose.
    private var etaText: TextView? = null
    private var etaDetail: TextView? = null
    private var devLine: TextView? = null
    private var playButton: ImageButton? = null
    private var voiceButton: ImageButton? = null

    /** Spoken turn cues and vibrations (#20); the announcer keeps them to one per maneuver. */
    private lateinit var voice: VoiceAndHaptics
    private val announcer = CueAnnouncer()

    private val loader = Executors.newSingleThreadExecutor()

    /** Name of the saved map on screen, or null for the bundled EH3 map. */
    private var mapName: String? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val stride = TrackingConfig().nominalStrideMeters
    private val router = AStarRouter()
    private val live by lazy {
        LiveMotionSource(
            this,
            ::onLivePose,
            onHeading = ::onLiveHeading,
            onStats = ::onLiveStats,
            onMoving = ::onLiveMoving,
            traceDir = getExternalFilesDir("sessions"),
            onCandidate = { t, verdict, bob ->
                if (following) logIo.execute { log?.candidate(t, verdict, bob) }
            },
        )
    }
    private val logIo = Executors.newSingleThreadExecutor()
    private var log: SessionLog? = null
    private var logFile: File? = null

    private var graph: FloorGraph? = null
    private var corridors: Map<String, String> = emptyMap()
    private var places: List<Place> = emptyList()
    private var walkPx: Map<String, Double> = emptyMap()
    private var search: PlaceSearch? = null
    private var roomFinder: RoomFinder? = null
    private var labelBoxes: Map<String, LabelBox> = emptyMap()

    private var screen = Screen.BROWSE
    private var place: Place? = null
    private var session: NavigationSession? = null
    private var guide: RouteGuide? = null
    private var walk: DemoWalker? = null
    private var demoScenario = DemoScenario.FOLLOW

    /** Where the walker is along the active route, and whether they left it (#80). */
    private var progress: RouteProgress? = null
    private val rerouteParams = RerouteParams()

    /** A new route is being shown: the banner says "Rerouting…" until it is applied. */
    private var rerouting = false
    private var pendingReroute: Runnable? = null
    private var turnAroundSaid = false

    /** Guide with temi (#temi): chosen on the route preview, run by [TemiGuide]. */
    private var guideWithTemi = false
    private var carry = Carry.HAND
    private var temi: TemiGuide? = null
    private lateinit var escortCard: LinearLayout
    private lateinit var escortTitle: TextView
    private lateinit var escortDetail: TextView
    private lateinit var escortAction: TextView
    private var escortShown: EscortState? = null

    /** Demo with temi: the walker stops once halfway, to show temi waiting for them. */
    private var demoPaused = false
    private var demoPauseUntil = 0L
    private var walking = false
    private var liveMode = false
    private var following = false
    private var resumeFollowing = false
    private var stats: LiveStats? = null
    private var announcedCalibrationNs: Long? = null
    private var checkpoints = 0
    private var startedAt = 0L
    private var lastPose: PoseEstimate? = null
    private var puckMotion: PuckMotion? = null
    private var liveTrail: Trail? = null
    private var stepsExpanded = false

    /** Live walks only: the four checks after every pose (#18), and what the chip shows. */
    private var monitor: FourCheckConfidenceMonitor? = null
    private var lastConfidence: ConfidenceState? = null
    private var shownLow = false
    private var highStreak = 0

    /** The live map heading, for the sign scan's bearings (#19). */
    private var liveHeadingRad: Double? = null

    /** The "Find a room sign" camera, while it is open. */
    private var signScan: SignScanView? = null

    private val tick = object : Runnable {
        override fun run() {
            val stream = walk ?: return
            val path = guide?.path ?: return
            if (temiHoldsWalker(path.lengthPx)) {
                ticker.postDelayed(this, Demo.STEP_INTERVAL_MS)
                return
            }
            onPose(stream.step(path))
            val active = guide?.path ?: return
            if (walking && !stream.finished(active)) {
                ticker.postDelayed(this, Demo.STEP_INTERVAL_MS)
            }
        }
    }

    private val hideSnack = Runnable {
        snack.animate().alpha(0f).translationY(dpf(8f)).setDuration(180).withEndAction {
            snack.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RobotHub.load(this)
        voice = VoiceAndHaptics(this).apply {
            voiceOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_VOICE, true)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, backHandler)
        show(Screen.BROWSE)
        loader.execute {
            val loaded = runCatching { loadMap() }
            runOnUiThread {
                loaded.onSuccess { (g, image) ->
                    graph = g
                    corridors = g.corridorIds()
                    map.bitmap = image
                    map.setPuck(g.nodes.getValue(g.startNodeId).position)
                    renderBrowseSheet()
                    if (intent.getBooleanExtra(EXTRA_JUST_SAVED, false)) {
                        showSnack(getString(R.string.map_saved))
                    }
                }.onFailure { showSnack(getString(R.string.map_failed)) }
            }
        }
    }

    /** Graph, image, every searchable place and its walking distance from START. */
    private fun loadMap(): Pair<FloorGraph, android.graphics.Bitmap> {
        val savedId = intent.getStringExtra(EXTRA_MAP_ID)
        val (loaded, image) = if (savedId == null) {
            MapAssets.loadGraph(this) to MapAssets.loadImage(this)
        } else {
            val store = MapAssets.savedMaps(this)
            val saved = store.get(savedId) ?: error("saved map $savedId is gone")
            runOnUiThread { mapName = saved.name }
            store.loadGraph(saved) to MapAssets.loadImage(saved)
        }
        // Routes, turn cues and tracking follow each corridor's straight line, not its trace.
        val g = loaded.smoothedCorridors()
        val all = g.places()
        val distances = all.mapNotNull { p ->
            val route = if (p.label != null) {
                router.routeToLabel(g, g.startNodeId, p.label)
            } else {
                router.route(g, g.startNodeId, p.nodeIds.first())
            }
            route?.let { p.key to it.lengthPx }
        }.toMap()
        val doors = all.filter { it.kind == PlaceKind.ROOM }.associate { room ->
            room.label!! to room.nodeIds.map { g.nodes.getValue(it).position }
        }
        val corridors = g.edges.flatMap { g.edgePoints(it).zipWithNext() }
        val walls = MapRooms.walls(image)
        val finder = RoomFinder(
            walls,
            emptyList(),
            doors,
            corridors = corridors,
            // Graph pixels follow the straightened image, so this scales with any map.
            corridorHalfWidthPx = image.width * CORRIDOR_HALF_WIDTH_SHARE,
        )
        runOnUiThread {
            places = all
            walkPx = distances
            val found = PlaceSearch(all) { distances[it.key] }
            search = found
            roomFinder = finder
            val kinds = found.available()
            featureChips.forEach { (kind, view) -> view.visibility = shown(kind in kinds) }
            chips.visibility = shown(screen == Screen.BROWSE && kinds.isNotEmpty())
        }
        // Reading the printed numbers takes a few seconds; taps work from doors until then.
        loader.execute {
            val boxes = MapRooms.labels(image, doors.keys, doors)
            runOnUiThread {
                finder.labels = boxes
                labelBoxes = boxes.associateBy { it.label }
                // A room picked before the numbers were read: move its pin onto its number.
                val picked = place?.label?.let { labelBoxes[it] }
                if (screen == Screen.PLACE && picked != null) map.pin = picked.center
            }
        }
        return g to image
    }

    override fun onResume() {
        super.onResume()
        if (!liveMode) return
        live.start()
        if (resumeFollowing) {
            resumeFollowing = false
            askFacing(again = true)
        }
    }

    override fun onPause() {
        super.onPause()
        voice.stop()
        setWalking(false)
        // Sensors stop with the screen, so live tracking re-anchors at START on return.
        resumeFollowing = following
        following = false
        live.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.shutdown()
        loader.shutdownNow()
        ticker.removeCallbacksAndMessages(null)
        logIo.execute { log?.close() }
        logIo.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                openSignScan()
            } else {
                showSnack(getString(R.string.sign_scan_camera_needed))
            }
            return
        }
        if (requestCode != STEP_PERMISSION) return
        if (!live.hasStepPermission) showSnack(getString(R.string.using_peaks))
        setLiveMode(true)
    }

    // ---- Screens ----

    private val backHandler = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (signScan != null) return closeSignScan()
            when (screen) {
                Screen.BROWSE -> finish()
                Screen.SEARCH, Screen.PLACE, Screen.ARRIVED -> clearPlace()
                Screen.PREVIEW -> openPlace(place ?: return clearPlace())
                Screen.FACING, Screen.NAVIGATE -> stopNavigation()
                Screen.CHECKPOINT -> endCheckpoint()
            }
        }
    }

    /** Shows the chrome for [next]; the sheet content is set by each screen's render call. */
    private fun show(next: Screen) {
        val previous = screen
        screen = next
        val navLike = next == Screen.NAVIGATE || next == Screen.CHECKPOINT || next == Screen.ARRIVED
        searchBar.visibility = shown(next == Screen.BROWSE || next == Screen.PLACE)
        val open = place.takeIf { next == Screen.PLACE }
        if (open != null) {
            searchLabel.text = placeTitle(open)
            searchLabel.setTextColor(Ui.INK)
        } else {
            searchLabel.setText(R.string.in_search_hint)
            searchLabel.setTextColor(Ui.INK_SECONDARY)
        }
        chips.visibility = shown(
            next == Screen.BROWSE && featureChips.values.any { it.visibility == View.VISIBLE },
        )
        routeCard.visibility = shown(next == Screen.PREVIEW)
        hintCard.visibility = shown(next == Screen.FACING || next == Screen.CHECKPOINT)
        locate.visibility = shown(!navLike && next != Screen.FACING)
        if (!navLike || next == Screen.CHECKPOINT) recenter.visibility = View.GONE
        setBannerVisible(next == Screen.NAVIGATE || next == Screen.ARRIVED)
        confidenceChip.visibility = shown(next == Screen.NAVIGATE && monitor != null)
        escortCard.visibility = shown(next == Screen.NAVIGATE && temi != null)
        // Every way into navigation starts in the heading-up view.
        if (next == Screen.NAVIGATE) compass.contentDescription = getString(R.string.in_compass)
        if (next == Screen.SEARCH) {
            openSearchPanel()
        } else if (previous == Screen.SEARCH) {
            closeSearchPanel()
        }
        map.navigating = next == Screen.NAVIGATE || next == Screen.CHECKPOINT
        // Like a maps app: tap a room to select it. Checkpoints install their own handler.
        if (next == Screen.BROWSE || next == Screen.PLACE) {
            map.onMapTap = ::pickRoomAt
        } else if (next != Screen.CHECKPOINT) {
            map.onMapTap = null
        }
        modeChip.text = getString(modeChipText(liveMode))
    }

    private fun setBannerVisible(visible: Boolean) {
        if (visible == (banner.visibility == View.VISIBLE)) return
        if (visible) {
            banner.visibility = View.VISIBLE
            banner.alpha = 0f
            banner.translationY = -dpf(24f)
            banner.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            banner.animate().cancel()
            banner.visibility = View.GONE
        }
    }

    /** Replaces the sheet content with a short rise-and-fade, so state changes read as one move. */
    private fun setSheet(vararg views: View) {
        sheet.removeAllViews()
        sheet.addView(handle())
        for (v in views) sheet.addView(v)
        sheet.alpha = 0f
        sheet.translationY = dpf(16f)
        sheet.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun renderBrowseSheet() {
        etaText = null
        setSheet(
            text(Ui.HEADING, bold = true).apply {
                text = mapName ?: getString(R.string.in_building)
                markHeading()
            },
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                val line = if (mapName == null) R.string.in_floor_line else R.string.in_at_start
                text = getString(line)
            }.withMargins(top = 4, bottom = 8),
        )
    }

    private fun clearPlace(moveCamera: Boolean = true) {
        stopNavigation(toBrowse = false)
        place = null
        map.pin = null
        map.pinLabel = null
        map.choicePins = emptyList()
        map.highlight = null
        map.highlightBox = null
        map.route = emptyList()
        show(Screen.BROWSE)
        renderBrowseSheet()
        if (moveCamera) graph?.let { map.focus(it.nodes.getValue(it.startNodeId).position) }
    }

    // ---- Search ----

    private fun openSearchPanel() {
        searchPanel.visibility = View.VISIBLE
        searchPanel.alpha = 0f
        searchPanel.animate().alpha(1f).setDuration(160).start()
        searchField.setText("")
        renderResults("")
        searchField.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearchPanel() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchPanel.animate().alpha(0f).setDuration(140).withEndAction {
            searchPanel.visibility = View.GONE
        }.start()
    }

    private fun renderResults(query: String) {
        results.removeAllViews()
        searchClear.visibility = if (query.isEmpty()) View.INVISIBLE else View.VISIBLE
        val engine = search ?: return
        val hits = if (query.isBlank()) {
            places.filter { it.kind == PlaceKind.ROOM }
                .sortedBy { walkPx[it.key] ?: Double.MAX_VALUE }
                .take(8)
                .map { PlaceMatch(it, null) }
        } else {
            engine.search(query)
        }
        results.addView(
            sectionLabel(if (query.isBlank()) R.string.in_nearby else R.string.in_results),
        )
        if (hits.isEmpty()) {
            results.addView(
                text(
                    Ui.BODY,
                    bold = true
                ).apply { text = getString(R.string.in_no_results, query.trim()) }
                    .withMargins(top = 16, start = 20, end = 20),
            )
            results.addView(
                text(Ui.CAPTION, Ui.INK_SECONDARY).apply { setText(R.string.in_no_results_hint) }
                    .withMargins(top = 4, start = 20, end = 20),
            )
            return
        }
        for (hit in hits) results.addView(resultRow(hit))
    }

    private fun resultRow(hit: PlaceMatch): View {
        val p = hit.place
        val title = SpannableString(placeTitle(p))
        hit.matched?.let { range ->
            val offset = placeTitle(p).length - (p.label?.length ?: 0)
            title.setSpan(
                StyleSpan(Typeface.BOLD),
                offset + range.first,
                offset + range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val meters = walkPx[p.key]?.let { pxToMeters(it, Demo.STEP_LENGTH_PX) }
        val detail = if (meters == null) {
            getString(R.string.in_unreachable)
        } else {
            getString(R.string.in_result_detail, placeKind(p), NavMath.roundedMeters(meters))
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = pressable(rounded(Color.TRANSPARENT, radiusDp = 0))
            isClickable = true
            contentDescription = "${placeTitle(p)}, $detail"
            setOnClickListener { openPlace(p) }
            addView(iconBubble(kindIcon(p.kind), Ui.INK_SECONDARY, Color.rgb(241, 243, 244)))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(Ui.BODY).apply { text = title })
                    addView(text(Ui.CAPTION, Ui.INK_SECONDARY).apply { text = detail })
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) },
            )
        }
    }

    // ---- Place ----

    /**
     * Selects [p]: pin on its printed number and its card. Until the numbers are read (a few
     * seconds after a map opens) the pin goes where the user tapped ([tap]), else on the door,
     * and moves onto the number once it is read.
     */
    private fun openPlace(p: Place, moveCamera: Boolean = true, tap: PixelPoint? = null) {
        stopNavigation(toBrowse = false)
        place = p
        map.choicePins = emptyList()
        val box = p.label?.let { labelBoxes[it] }
        val at = box?.center ?: tap ?: p.position
        map.route = emptyList()
        clearRoomTint()
        map.pin = at
        map.pinLabel = p.label ?: placeTitle(p)
        show(Screen.PLACE)
        // A room tapped on the map is already in view: keep the camera still, as maps do.
        if (moveCamera) map.focus(at)
        val meters = walkPx[p.key]?.let { pxToMeters(it, Demo.STEP_LENGTH_PX) }
        val summary = text(Ui.BODY, Ui.INK_SECONDARY).apply {
            text = meters?.let {
                val minutes = NavMath.walkingMinutes(it)
                getString(R.string.in_walk_summary, minutes, NavMath.roundedMeters(it))
            } ?: getString(R.string.in_unreachable)
            setCompoundDrawablesRelative(
                icon(R.drawable.ic_walk, Ui.INK_SECONDARY, 20),
                null,
                null,
                null
            )
            compoundDrawablePadding = dp(8)
        }
        val directions = styledButton(
            ButtonStyle.PRIMARY,
            getString(R.string.in_directions),
            R.drawable.ic_directions
        )
            .apply {
                isEnabled = meters != null
                setOnClickListener { preview(p) }
            }
        val start = styledButton(
            ButtonStyle.SECONDARY,
            getString(R.string.in_start),
            R.drawable.ic_navigation
        )
            .apply {
                isEnabled = meters != null
                setOnClickListener { if (plan(p)) beginNavigation() }
            }
        setSheet(
            text(Ui.TITLE, bold = true).apply {
                text = placeTitle(p)
                markHeading()
            },
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply { text = placeKind(p) }.withMargins(top = 2),
            summary.withMargins(top = 12),
            LinearLayout(this).apply {
                addView(directions, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(
                    start,
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) }
                )
            }.withMargins(top = 16, bottom = 4),
        )
    }

    /** A tap on the map: select the room under it, or deselect when there is none. */
    private fun pickRoomAt(tap: PixelPoint) {
        // While choosing an elevator, a tap near one of its pins picks it.
        val reach = map.mapDistance(dpf(TAP_DOOR_DP))
        val chosen = map.choicePins.withIndex()
            .filter { it.value.distanceTo(tap) <= 2 * reach }
            .minByOrNull { it.value.distanceTo(tap) }
        if (chosen != null) {
            places.firstOrNull { it.position == chosen.value }?.let { return openPlace(it) }
        }
        // Exits, elevators and AEDs are places too: a tap on or beside one opens it, like a
        // room. Their sign is printed beside the corridor point, so the reach is wider.
        val feature = places.filter { it.kind != PlaceKind.ROOM }
            .map { it to it.position.distanceTo(tap) }
            .filter { (_, d) -> d <= FEATURE_TAP_REACH * reach }
            .minByOrNull { (_, d) -> d }?.first
        if (feature != null) return openPlace(feature, moveCamera = false)
        val finder = roomFinder ?: return
        val hit = finder.roomAt(tap, map.mapDistance(dpf(TAP_DOOR_DP)))
        val target = hit?.let { h -> places.firstOrNull { it.label == h.label } }
        when {
            target != null -> openPlace(target, moveCamera = false, tap = tap)
            hit != null -> openUnmapped(hit.label)
            screen == Screen.PLACE -> clearPlace(moveCamera = false)
        }
    }

    /**
     * Rooms are marked by the pin alone. A tinted outline claimed an exact room shape that a
     * poster photo can't give (walls, doors and print run together), so none is drawn.
     */
    private fun clearRoomTint() {
        map.highlightBox = null
        map.highlight = null
    }

    /** A printed room the traced graph has no door for: name it, but offer no route. */
    private fun openUnmapped(label: String) {
        stopNavigation(toBrowse = false)
        place = null
        map.route = emptyList()
        clearRoomTint()
        map.pin = labelBoxes[label]?.center
        map.pinLabel = label
        show(Screen.PLACE)
        setSheet(
            text(Ui.TITLE, bold = true).apply {
                text = getString(R.string.in_room_title, label)
                markHeading()
            },
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply { text = roomKind() }
                .withMargins(top = 2),
            text(Ui.BODY, Ui.INK_SECONDARY).apply { setText(R.string.in_unmapped) }
                .withMargins(top = 12, bottom = 8),
        )
    }

    /** Routes from START to [p] in a fresh session; false (with a message) if unreachable. */
    private fun plan(p: Place): Boolean {
        val g = graph ?: return false
        val next = NavigationSession(router)
        next.onMapCaptured(g)
        // A map that fails the checks gets a message, never a crash.
        val verified = runCatching { next.onGraphVerified(g) }
        if (verified.isFailure) {
            android.util.Log.e(PERF_TAG, "map failed its checks", verified.exceptionOrNull())
            showSnack(getString(R.string.no_route, placeTitle(p)))
            return false
        }
        val ok = if (p.label != null) {
            next.onCalibratedToRoom(Demo.STEP_LENGTH_PX, p.label)
        } else {
            next.onCalibrated(Demo.STEP_LENGTH_PX, p.nodeIds.first())
        }
        if (!ok) {
            showSnack(getString(R.string.no_route, placeTitle(p)))
            return false
        }
        session = next
        guide = RouteGuide(checkNotNull(next.graph), checkNotNull(next.route))
        progress = RouteProgress(guide!!.path, rerouteParams)
        announcer.reset()
        val path = guide!!.path
        map.route = path.points
        map.routeWalkedPx = 0.0
        map.pin = path.points.last()
        return true
    }

    // ---- Preview ----

    private fun preview(p: Place) {
        if (!plan(p)) return
        val g = checkNotNull(guide)
        show(Screen.PREVIEW)
        routeTo.text = placeTitle(p)
        map.fit(g.path.points)
        renderPreviewSheet()
    }

    private fun renderPreviewSheet() {
        val g = guide ?: return
        val p = place ?: return
        val k = Demo.STEP_LENGTH_PX
        val meters = pxToMeters(g.path.lengthPx, k)
        val steps = (g.path.lengthPx / k).roundToInt()
        val time = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(
                text(Ui.TITLE, ETA_GREEN, bold = true).apply {
                    text = getString(R.string.in_minutes, NavMath.walkingMinutes(meters))
                },
            )
            addView(
                text(Ui.HEADING, Ui.INK_SECONDARY).apply {
                    text = getString(R.string.in_meters_paren, NavMath.roundedMeters(meters))
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginStart = dp(8)
                    bottomMargin = dp(2)
                },
            )
        }
        val modes = LinearLayout(this).apply {
            addView(modeToggle(R.string.in_mode_demo, !liveMode) { if (liveMode) toggleMode() })
            addView(
                modeToggle(R.string.in_mode_live, liveMode) { if (!liveMode) toggleMode() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
            )
        }
        // Demo only: which walk to play, so rerouting can be shown without leaving the desk.
        val scenarios = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = shown(!liveMode)
            addView(
                LinearLayout(context).apply {
                    DemoScenario.entries.forEachIndexed { i, scenario ->
                        addView(
                            modeToggle(scenarioLabel(scenario), scenario == demoScenario) {
                                demoScenario = scenario
                                renderPreviewSheet()
                            },
                            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                                if (i > 0) marginStart = dp(8)
                            },
                        )
                    }
                },
            )
        }
        val stepsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (stepsExpanded) View.VISIBLE else View.GONE
            for (row in directionRows(g, k)) addView(row)
        }
        val stepsHeader = TextView(this).apply {
            setText(R.string.in_steps)
            textSize = Ui.BODY
            setTextColor(Ui.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(Ui.TOUCH_DP)
            setCompoundDrawablesRelative(
                null,
                null,
                icon(R.drawable.ic_expand, Ui.ACCENT, 20),
                null
            )
            compoundDrawablePadding = dp(4)
            setOnClickListener {
                stepsExpanded = !stepsExpanded
                stepsList.visibility = if (stepsExpanded) View.VISIBLE else View.GONE
            }
        }
        val start = styledButton(
            ButtonStyle.PRIMARY,
            getString(R.string.in_start),
            R.drawable.ic_navigation
        ).apply {
            minHeight = dp(56)
            setOnClickListener { beginNavigation() }
        }
        val scroll = ScrollView(this).apply {
            isVerticalFadingEdgeEnabled = true
            addView(stepsList)
        }
        setSheet(
            time,
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                text = resources.getQuantityString(R.plurals.in_route_line, steps, steps)
                contentDescription = "${placeTitle(p)}. $text"
            }.withMargins(top = 2),
            modes.withMargins(top = 16),
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                setText(R.string.demo_scenario_title)
                visibility = shown(!liveMode)
            }.withMargins(top = 12),
            scenarios.withMargins(top = 6),
            *guideChoice().toTypedArray(),
            stepsHeader.withMargins(top = 8),
            scroll.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            },
            start.withMargins(top = 8, bottom = 4),
        )
        if (stepsExpanded) capHeight(scroll)
    }

    /** Keeps the steps list from pushing the Start button off a short screen. */
    private fun capHeight(scroll: ScrollView) {
        scroll.post {
            val max = (root.height * 0.32f).toInt()
            if (scroll.height > max) scroll.layoutParams.height = max
            scroll.requestLayout()
        }
    }

    /** One row per maneuver: what to do, where, and how far after the previous one. */
    private fun directionRows(g: RouteGuide, k: Double): List<View> {
        var previous = 0.0
        return g.cues.map { cue ->
            val instruction = g.instruction(poseAt(g, previous + 0.01, k))
            val meters = pxToMeters(cue.distancePx - previous, k)
            previous = cue.distancePx
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                addView(
                    ManeuverView(context).apply {
                        maneuver = cue.maneuver
                        color = Ui.INK_SECONDARY
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)),
                )
                addView(
                    text(Ui.BODY).apply {
                        text = getString(
                            R.string.in_step_row,
                            maneuverText(cue.maneuver, stepsUntil = 1),
                            instruction.detail,
                        )
                    },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) },
                )
                addView(
                    text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                        text = getString(R.string.in_step_distance, NavMath.roundedMeters(meters))
                    },
                )
            }
        }
    }

    private fun poseAt(g: RouteGuide, s: Double, k: Double) = PoseEstimate(
        timestampNanos = 0,
        position = g.path.pointAt(s),
        headingRad = g.path.bearingAt(s),
        edgeId = g.path.edgeAt(s),
        spreadPx = 0.0,
        topClusterWeight = 1.0,
        stepLengthPx = k,
    )

    // ---- Navigation ----

    private fun beginNavigation() {
        val g = guide ?: return
        val p = place ?: return
        stats = null
        announcedCalibrationNs = null
        checkpoints = 0
        map.checkpoints = emptyList()
        lastPose = null
        progress = RouteProgress(g.path, rerouteParams)
        turnAroundSaid = false
        setRerouting(null)
        if (liveMode) {
            walk = null
            askFacing(again = false)
            return
        }
        val graph0 = graph ?: return
        val stream = DemoWalker(graph0, g.path, demoScenario, Demo.STEP_LENGTH_PX)
        walk = stream
        val mode = "demo-" + demoScenario.name.lowercase().replace('_', '-')
        openLog(mode, p.label ?: p.key, g.path.bearingAt(0.0))
        startedAt = SystemClock.elapsedRealtime()
        show(Screen.NAVIGATE)
        renderNavigateSheet()
        val first = stream.current()
        map.setHeading(first.headingRad)
        startTemi()
        onPose(first)
        map.startFollowing(IndoorMapView.Follow.HEADING)
        // Give the camera time to swing in before the walker sets off.
        ticker.postDelayed(
            { if (screen == Screen.NAVIGATE && walk === stream) setWalking(true) },
            900
        )
    }

    private fun stopNavigation(toBrowse: Boolean = true) {
        voice.stop()
        announcer.reset()
        pendingReroute?.let(ticker::removeCallbacks)
        pendingReroute = null
        closeSignScan()
        stopTemi()
        setRerouting(null)
        setWalking(false)
        following = false
        live.pause()
        walk = null
        map.facingChoices = emptyList()
        map.onFacingTap = null
        map.onMapTap = null
        map.navigating = false
        map.setHeading(null)
        map.driver = null
        puckMotion = null
        liveTrail = null
        monitor = null
        lastConfidence = null
        map.uncertain = false
        confidenceChip.visibility = View.GONE
        graph?.let { map.setPuck(it.nodes.getValue(it.startNodeId).position, durationMs = 500) }
        map.routeWalkedPx = 0.0
        if (lastPose != null) logIo.execute { log?.close().also { log = null } }
        lastPose = null
        if (toBrowse) {
            val p = place
            if (p != null) preview(p) else clearPlace()
        }
    }

    /** START: tap the corridor you face, on the map or in the list. Its bearing sets θ0. */
    private fun askFacing(again: Boolean) {
        val g = graph ?: return
        val options = g.facingOptions(g.startNodeId)
        show(Screen.FACING)
        hintTitle.setText(R.string.in_facing_title)
        hintBody.setText(if (again) R.string.in_facing_again else R.string.in_facing_body)
        map.setPuck(g.nodes.getValue(g.startNodeId).position)
        map.setHeading(null)
        map.facingChoices = options.map { it.bearingRad }
        map.onFacingTap = { i -> startLive(g, options[i]) }
        map.focus(g.nodes.getValue(g.startNodeId).position, northUp = true)
        val rows = options.map { option ->
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                background = pressable(rounded(Color.TRANSPARENT, radiusDp = 12))
                isClickable = true
                setOnClickListener { startLive(g, option) }
                addView(
                    iconBubble(R.drawable.ic_navigation, Ui.ACCENT, Ui.ACCENT_SOFT).apply {
                        // The facing step shows the map poster-up, so map bearing −90° is up.
                        rotation = Math.toDegrees(option.bearingRad).toFloat() + 90f
                    },
                    LinearLayout.LayoutParams(dp(40), dp(40)),
                )
                addView(
                    text(
                        Ui.BODY
                    ).apply { text = option.description.replaceFirstChar { it.uppercase() } },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) },
                )
            }
        }
        val cancel = styledButton(ButtonStyle.SECONDARY, getString(R.string.in_cancel)).apply {
            setOnClickListener { stopNavigation() }
        }
        setSheet(*rows.toTypedArray(), cancel.withMargins(top = 8, bottom = 4))
    }

    private fun startLive(g: FloorGraph, facing: FacingOption) {
        val p = place ?: return
        val k = Demo.STEP_LENGTH_PX
        map.facingChoices = emptyList()
        map.onFacingTap = null
        live.begin(
            facing.bearingRad,
            ParticleTracker(),
            setup = { it.start(g, g.startNodeId, facing.bearingRad, k) },
        ) { aligned ->
            if (!aligned) {
                showSnack(getString(R.string.hold_flat))
                askFacing(again = false)
                return@begin
            }
            following = true
            monitor = FourCheckConfidenceMonitor()
            lastConfidence = null
            highStreak = 0
            renderConfidence(low = false, state = null)
            openLog("live", p.label ?: p.key, facing.bearingRad)
            startedAt = SystemClock.elapsedRealtime()
            show(Screen.NAVIGATE)
            renderNavigateSheet()
            map.setHeading(facing.bearingRad)
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
            startTemi()
            map.startFollowing(IndoorMapView.Follow.HEADING)
        }
    }

    private fun onLiveHeading(mapRad: Double) {
        liveHeadingRad = mapRad
        if (following) map.setHeading(mapRad, minimumChangeRad = Math.toRadians(0.5))
    }

    /** A detected step that didn't move the user: refresh the readout so shaking shows up. */
    private fun onLiveStats(latest: LiveStats) {
        if (!following) return
        stats = latest
        renderDevLine(lastPose?.stepLengthPx ?: Demo.STEP_LENGTH_PX)
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
            showSnack(getString(R.string.recalibrated, k))
        }
        onPose(pose)
    }

    /** One pose update: session first (the single writer), then everything that renders it. */
    private fun onPose(pose: PoseEstimate) {
        val began = SystemClock.elapsedRealtimeNanos()
        try {
            handlePose(pose)
        } finally {
            val ms = (SystemClock.elapsedRealtimeNanos() - began) / 1e6
            if (ms > SLOW_UI_MS) android.util.Log.w(PERF_TAG, "pose took %.1f ms".format(ms))
        }
    }

    private fun handlePose(pose: PoseEstimate) {
        val s = session ?: return
        val g = guide ?: return
        s.onPose(pose)
        lastPose = pose
        logIo.execute { log?.pose(pose) }
        val m = monitor
        val g0 = graph
        if (m != null && g0 != null) {
            val state = m.evaluate(g0, pose)
            s.onConfidence(state)
            onConfidence(state)
        }
        if (liveMode) {
            followLive(pose)
        } else {
            map.setPuck(pose.position, durationMs = Demo.STEP_INTERVAL_MS, linear = true)
            map.setHeading(pose.headingRad)
        }
        val tracked = progress ?: return
        val status = tracked.update(pose)
        temi?.onWalker(if (carry == Carry.HAND) status.progressPx else null, pose.position)
        if (map.driver == null) map.routeWalkedPx = status.progressPx
        // While "Rerouting…" shows, the old route's instructions would be wrong: hold them.
        if (rerouting) return
        val reason = status.reroute
        // Never reroute from a position we aren't sure of: wait for confidence to come back.
        if (reason != null && !shownLow && reroute(reason, pose, status)) return
        // Off the line but not yet confirmed: keep the last instruction, like a maps app,
        // rather than skipping ahead to a turn the walker hasn't reached.
        if (status.offRoute) return
        guideFrom(g, pose, status)
    }

    /** Banner, ETA, voice and arrival for one pose at [status] along route [g]. */
    private fun guideFrom(g: RouteGuide, pose: PoseEstimate, status: RouteStatus) {
        val s = session ?: return
        val k = pose.stepLengthPx
        val at = status.progressPx
        val instruction = if (status.facingBack) {
            Instruction(Maneuver.U_TURN, 0, getString(R.string.in_turn_around_detail))
        } else {
            g.instruction(at, k)
        }
        renderBanner(g, at, k, instruction, turnAround = status.facingBack)
        renderEta(g, at, k)
        if (status.facingBack) {
            // Once per spell of facing the wrong way, like a maps app's "Make a U-turn".
            if (!turnAroundSaid) {
                turnAroundSaid = true
                voice.announce(Announcement(getString(R.string.in_turn_around), Haptic.TURN))
            }
            return
        }
        turnAroundSaid = false
        val upcoming = g.cues.indexOfFirst { it.distancePx > at }
        announcer.next(instruction, upcoming)?.let(voice::announce)
        if (instruction.maneuver == Maneuver.ARRIVE && instruction.stepsUntil == 0 &&
            s.phase != Phase.ARRIVED
        ) {
            s.onArrived()
            arrive(pose)
        }
    }

    /**
     * Off route or walking the wrong way: work out a new route from where the walker is,
     * show "Rerouting…" for a moment so the change is never silent, then swap it in. The dot
     * keeps following the tracker throughout; only the route line and the guidance change.
     * Returns false if there is no way from here (the old route stays, with a message).
     */
    private fun reroute(reason: RerouteReason, pose: PoseEstimate, status: RouteStatus): Boolean {
        val g = graph ?: return false
        val p = place ?: return false
        val next = router.rerouteTo(g, p, pose, rerouteParams)
        if (next == null) {
            showSnack(getString(R.string.no_route, placeTitle(p)))
            // Ask again only after a cooldown, not on every step.
            guide?.let {
                progress = RouteProgress(
                    it.path,
                    rerouteParams,
                    startPx = status.progressPx,
                    cooldownSteps = REROUTE_RETRY_STEPS,
                )
            }
            return false
        }
        logIo.execute { log?.reroute(pose, reason.name, next.route.lengthPx) }
        voice.stop()
        voice.announce(Announcement(getString(R.string.in_rerouting_voice), null))
        setRerouting(reason)
        val apply = Runnable { applyReroute(next) }
        pendingReroute = apply
        ticker.postDelayed(apply, REROUTE_SHOW_MS)
        return true
    }

    /** Swaps in [next]: session first (the single writer of the route), then map and banner. */
    private fun applyReroute(next: Reroute) {
        pendingReroute = null
        val s = session ?: return
        val g = graph ?: return
        val pose = lastPose ?: return
        s.onRerouted(next.route)
        val newGuide = RouteGuide(g, next.route)
        guide = newGuide
        temi?.rerouted(newGuide)
        val start = newGuide.path.project(pose.position)
        val tracked = RouteProgress(
            newGuide.path,
            rerouteParams,
            startPx = start,
            cooldownSteps = rerouteParams.cooldownSteps,
            startFacingBack = next.turnAround,
        )
        progress = tracked
        announcer.reset()
        turnAroundSaid = false
        map.route = newGuide.path.points
        map.routeWalkedPx = start
        map.pin = newGuide.path.points.last()
        setRerouting(null)
        val meters = pxToMeters(newGuide.path.lengthPx - start, pose.stepLengthPx)
        showSnack(getString(R.string.in_new_route, NavMath.roundedMeters(meters)))
        guideFrom(
            newGuide,
            pose,
            RouteStatus(
                start,
                0.0,
                offRoute = false,
                facingBack = tracked.facingBack,
                reroute = null
            ),
        )
    }

    /** Shows the grey "Rerouting…" banner for [reason], or the normal green one for null. */
    private fun setRerouting(reason: RerouteReason?) {
        rerouting = reason != null
        val tone = if (reason != null) BANNER_GREY else BANNER_GREEN
        bannerCard.background = rounded(tone, radiusDp = 20)
        bannerSpinner.visibility = shown(reason != null)
        bannerIcon.visibility = shown(reason == null)
        if (reason == null) return
        bannerTitle.setText(R.string.in_rerouting)
        bannerDetail.setText(
            if (reason == RerouteReason.OFF_ROUTE) R.string.in_off_route else R.string.in_wrong_way,
        )
        bannerDistance.visibility = View.GONE
        thenStrip.visibility = View.GONE
        banner.contentDescription = "${bannerTitle.text}. ${bannerDetail.text}"
    }

    /**
     * Live walking: the dot moves every frame at walking speed ([PuckMotion]) along the trail
     * of tracker positions ([Trail]), so it follows the user through turns, U-turns and
     * detours alike. Each tracker pose only corrects the speed, so the dot neither waits for
     * the next step nor jumps; route progress and guidance still come from the pose itself.
     */
    private fun followLive(pose: PoseEstimate) {
        val trail = liveTrail ?: Trail(pose.position).also { liveTrail = it }
        val walked = trail.add(pose.position)
        map.setPuck(pose.position, pose.spreadPx, durationMs = 0L)
        val motion = puckMotion ?: PuckMotion(snapPx = SNAP_STEPS * pose.stepLengthPx).also {
            it.reset(walked)
            puckMotion = it
        }
        val now = SystemClock.uptimeMillis() / 1000.0
        motion.onStep(walked, now, pose.stepLengthPx, pose.timestampNanos)
        if (map.driver == null) {
            map.driver = object : IndoorMapView.PuckDriver {
                private var shown = pose.position

                override fun positionAt(nowSeconds: Double): PixelPoint {
                    shown = trail.pointAt(motion.advance(nowSeconds))
                    return shown
                }

                // Read the active route each frame: a reroute replaces it mid-walk.
                override val walkedPx: Double
                    get() = progress?.displayPx(shown, lastPose?.stepLengthPx ?: 1.0) ?: 0.0
                override val animating get() = motion.animating
            }
        }
    }

    /** The phone stopped or started bobbing: lets the dot stop within half a second. */
    private fun onLiveMoving(moving: Boolean) {
        if (!following) return
        puckMotion?.setMoving(moving)
        if (map.driver != null) map.invalidate()
    }

    /**
     * A new [ConfidenceState]: logged when it changes, and shown on the chip and the puck.
     * LOW shows at once; going back to HIGH waits for [HIGH_HOLD_STEPS] HIGH steps in a row,
     * so a cloud hovering at a threshold doesn't make the chip flicker.
     */
    private fun onConfidence(state: ConfidenceState) {
        val previous = lastConfidence
        lastConfidence = state
        if (previous == null || previous.level != state.level ||
            previous.failedChecks != state.failedChecks ||
            previous.suggestedRecovery != state.suggestedRecovery
        ) {
            logIo.execute { log?.confidence(state) }
        }
        val low = state.level == ConfidenceLevel.LOW
        highStreak = if (low) 0 else highStreak + 1
        val showLow = low || (shownLow && highStreak < HIGH_HOLD_STEPS)
        renderConfidence(showLow, if (low) state else null)
    }

    /** The chip under the banner: a calm one-liner when on track, reason and next step if not. */
    private fun renderConfidence(low: Boolean, state: ConfidenceState?) {
        shownLow = low
        map.uncertain = low
        confidenceChip.background = rounded(
            if (low) CONF_LOW_SOFT else Ui.SURFACE,
            radiusDp = 18,
            stroke = if (low) CONF_LOW_EDGE else null,
        )
        (confidenceDot.background as GradientDrawable).setColor(if (low) CONF_LOW else ETA_GREEN)
        confidenceTitle.setText(if (low) R.string.conf_low else R.string.conf_high)
        confidenceTitle.setTextColor(if (low) CONF_LOW_INK else Ui.INK)
        // While easing back to HIGH (state null) the last reason stays, so the text doesn't jump.
        if (low && state != null) {
            val reason = when {
                ConfidenceCheck.TURN_FIT in state.failedChecks -> R.string.conf_reason_turn
                ConfidenceCheck.HEADING_FIT in state.failedChecks -> R.string.conf_reason_heading
                ConfidenceCheck.GROUP_SPLIT in state.failedChecks -> R.string.conf_reason_split
                else -> R.string.conf_reason_spread
            }
            val action = if (state.suggestedRecovery == RecoveryAction.SCAN_SIGN) {
                R.string.conf_do_scan
            } else {
                R.string.conf_do_turn
            }
            confidenceDetail.text =
                getString(R.string.conf_detail, getString(reason), getString(action))
        }
        confidenceDetail.visibility = shown(low)
        confidenceAction.visibility = shown(low)
        val described = if (low) {
            "${confidenceTitle.text}. ${confidenceDetail.text}"
        } else {
            confidenceTitle.text
        }
        confidenceChip.contentDescription = "$described. ${getString(R.string.conf_tap_to_scan)}"
    }

    // ---- Guide with temi (#temi) ----

    /** Route preview: phone only or with temi, and how the phone is carried; only if temi is up. */
    private fun guideChoice(): List<View> {
        if (!RobotHub.connected) {
            guideWithTemi = false
            return emptyList()
        }
        val guideRow = LinearLayout(this).apply {
            addView(modeToggle(R.string.guide_phone, !guideWithTemi) { pickGuide(false) })
            addView(
                modeToggle(R.string.guide_temi, guideWithTemi) { pickGuide(true) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
            )
        }
        val carryRow = LinearLayout(this).apply {
            visibility = shown(guideWithTemi)
            addView(modeToggle(R.string.carry_hand, carry == Carry.HAND) { pickCarry(Carry.HAND) })
            addView(
                modeToggle(
                    R.string.carry_pocket,
                    carry == Carry.POCKET
                ) { pickCarry(Carry.POCKET) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
            )
        }
        return listOf(
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply { setText(R.string.guide_title) }
                .withMargins(top = 12),
            guideRow.withMargins(top = 6),
            carryRow.withMargins(top = 8),
        )
    }

    private fun pickGuide(withTemi: Boolean) {
        guideWithTemi = withTemi
        renderPreviewSheet()
    }

    private fun pickCarry(next: Carry) {
        carry = next
        renderPreviewSheet()
    }

    /** Starts the escort for the route on screen, if the walker chose temi. */
    private fun startTemi() {
        stopTemi()
        if (!guideWithTemi) return
        if (!RobotHub.connected) return showSnack(getString(R.string.temi_not_connected))
        val g = guide ?: return
        val graph0 = graph ?: return
        val pxPerMeter = Demo.STEP_LENGTH_PX / stride
        // The simulator starts each escort at START, next to the walker.
        if (RobotHub.setup.simulator) {
            RobotHub.connect(graph0.nodes.getValue(graph0.startNodeId).position, pxPerMeter)
        }
        val alignment = RobotHub.alignment(pxPerMeter)
            ?: return showSnack(getString(R.string.temi_needs_alignment))
        RobotHub.userAt = { lastPose?.position }
        RobotHub.onSimulatedSpeech = { line -> showSnack(getString(R.string.temi_says, line)) }
        demoPaused = false
        demoPauseUntil = 0L
        escortShown = null
        val listener = object : TemiGuide.Listener {
            override fun onRobot(at: PixelPoint, bearing: Double) {
                map.robotBearing = bearing
                map.robot = at
            }

            override fun onEscort(state: EscortState, gapMeters: Double?) =
                renderEscort(state, gapMeters)

            override fun onCue(cue: EscortCue, phoneText: String) {
                val buzz = cue == EscortCue.OPEN_DOOR || cue == EscortCue.WAITING ||
                    cue == EscortCue.MEET
                voice.announce(Announcement(phoneText, if (buzz) Haptic.TURN else null))
            }
        }
        val t = TemiGuide(
            this,
            g,
            carry,
            pxPerMeter,
            alignment,
            place?.let { placeTitle(it) }.orEmpty(),
            demoDoor = !liveMode,
            listener = listener,
        )
        temi = t
        escortCard.visibility = View.VISIBLE
        renderEscort(EscortState.STARTING, null)
        t.start()
    }

    private fun stopTemi() {
        temi?.stop()
        temi = null
        map.robot = null
        escortCard.visibility = View.GONE
    }

    /** The card under the banner: what temi is doing and what the walker should do. */
    private fun renderEscort(state: EscortState, gapMeters: Double?) {
        val t = temi
        val (title, detail) = when (state) {
            EscortState.STARTING -> getString(R.string.temi_card_starting) to ""
            EscortState.LEADING -> getString(R.string.temi_card_leading) to
                if (carry == Carry.HAND && gapMeters != null) {
                    getString(R.string.temi_card_gap, gapMeters.coerceAtLeast(0.0))
                } else {
                    getString(R.string.temi_card_pocket)
                }
            EscortState.WAITING_FOR_USER ->
                getString(R.string.temi_card_waiting) to
                    getString(R.string.temi_card_waiting_detail)
            EscortState.MEETING ->
                getString(R.string.temi_card_meeting) to
                    getString(R.string.temi_card_meeting_detail)
            EscortState.DOOR ->
                getString(R.string.temi_card_door) to getString(R.string.temi_card_door_detail)
            EscortState.BLOCKED -> getString(R.string.temi_card_blocked) to getString(
                if (t?.controller?.gaveUp == true) {
                    R.string.temi_card_gave_up
                } else {
                    R.string.temi_card_blocked_detail
                },
            )
            EscortState.ARRIVED ->
                getString(R.string.temi_card_arrived) to place?.let { placeTitle(it) }.orEmpty()
        }
        escortTitle.text = title
        escortDetail.text = detail
        escortDetail.visibility = shown(detail.isNotEmpty())
        escortAction.visibility = shown(state == EscortState.DOOR)
        escortCard.background = rounded(
            if (state == EscortState.DOOR) CONF_LOW_SOFT else Ui.SURFACE,
            radiusDp = 18,
            stroke = if (state == EscortState.DOOR) CONF_LOW_EDGE else null,
        )
        escortCard.contentDescription = "$title. $detail"
        // Demo: the scripted walker opens the door a moment after temi asks.
        if (state == EscortState.DOOR && escortShown != EscortState.DOOR && !liveMode) {
            ticker.postDelayed({ temi?.doorOpened() }, DEMO_DOOR_MS)
        }
        escortShown = state
    }

    /**
     * Demo with temi: the walker follows temi like a person, staying just behind it, and
     * stops once halfway for a few seconds so temi's waiting can be seen.
     */
    private fun temiHoldsWalker(routePx: Double): Boolean {
        val t = temi ?: return false
        val along = progress?.progressPx ?: 0.0
        val now = SystemClock.uptimeMillis()
        if (!demoPaused && along > 0.5 * routePx) {
            demoPaused = true
            demoPauseUntil = now + DEMO_PAUSE_MS
        }
        return now < demoPauseUntil || !t.walkerMayStep(along)
    }

    // ---- Room-sign scan (#19) ----

    /**
     * Opens "Find a room sign" over the map: the camera reads room numbers, and a steady one
     * goes to the tracker, which re-anchors if the door is near where it thinks the user is.
     * Live walks only (a demo walk has no real position to fix).
     */
    private fun openSignScan() {
        if (signScan != null) return
        val g = graph ?: return
        if (monitor == null) {
            showSnack(getString(R.string.sign_scan_live_only))
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
            return
        }
        val reader = SignReader(g, TrackingConfig().signTextHeightMeters)
        val view = SignScanView(
            this,
            this,
            reader,
            heading = { liveHeadingRad },
            onStable = ::submitSign,
            onClose = ::closeSignScan,
        )
        signScan = view
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180).start()
        view.requestApplyInsets()
        view.start()
    }

    private fun closeSignScan() {
        val view = signScan ?: return
        signScan = null
        view.stop()
        view.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(view) }.start()
    }

    /** A room read steadily: hand it to the tracker on the live worker. */
    private fun submitSign(reads: List<SignDetection>) {
        val k = lastPose?.stepLengthPx ?: Demo.STEP_LENGTH_PX
        val label = reads.first().label
        // The printed number tells the tracker which side of the corridor the sign is on.
        val sighting = SignVoter.sighting(reads, SystemClock.elapsedRealtimeNanos(), k / stride)
            .copy(roomCenter = labelBoxes[label]?.center)
        val meters = reads.mapNotNull { it.distanceMeters }.sorted().let {
            it.getOrNull(
                it.size / 2
            )
        }
        live.sign(sighting) { result, pose -> onSignResult(label, meters, result, pose) }
    }

    /** Never a silent change: an accepted sign moves the dot with a message; a rejected one says why. */
    private fun onSignResult(
        label: String,
        meters: Double?,
        result: SignResult?,
        pose: PoseEstimate?
    ) {
        val view = signScan
        if (result == null) {
            closeSignScan()
            return
        }
        val at = pose?.timestampNanos ?: SystemClock.elapsedRealtimeNanos()
        logIo.execute { log?.sign(at, label, result, pose) }
        if (!result.accepted) {
            view?.showResult(label, accepted = false, tooFar = result.reason == SignOutcome.TOO_FAR)
            return
        }
        view?.showResult(label, accepted = true, tooFar = false)
        ticker.postDelayed({ closeSignScan() }, SIGN_FOUND_MS)
        // The cloud was just re-anchored: confidence starts afresh from here.
        monitor?.reset()
        showSnack(
            when {
                result.relocated -> getString(R.string.sign_scan_moved, label)
                result.headingFixRad != null -> getString(R.string.sign_scan_anchored_facing, label)
                meters != null -> getString(R.string.sign_scan_anchored, label, meters)
                else -> getString(R.string.sign_scan_anchored_near, label)
            },
        )
        if (pose != null) onPose(pose)
    }

    private fun renderBanner(
        g: RouteGuide,
        s: Double,
        k: Double,
        instruction: Instruction,
        turnAround: Boolean = false,
    ) {
        val arrived = instruction.maneuver == Maneuver.ARRIVE && instruction.stepsUntil == 0
        bannerIcon.maneuver = instruction.maneuver
        bannerTitle.update(maneuverText(instruction.maneuver, instruction.stepsUntil))
        val meters = NavMath.roundedMeters(instruction.stepsUntil * stride)
        val noDistance = arrived || turnAround
        bannerDistance.update(if (noDistance) "" else getString(R.string.in_step_distance, meters))
        bannerDistance.visibility = if (noDistance) View.GONE else View.VISIBLE
        bannerDetail.update(
            if (turnAround) {
                instruction.detail
            } else if (arrived) {
                place?.let { placeTitle(it) }
            } else {
                val n = instruction.stepsUntil
                resources.getQuantityString(R.plurals.in_banner_detail, n, instruction.detail, n)
            },
        )
        banner.contentDescription = "${bannerTitle.text}, ${bannerDetail.text}"
        // "Then" preview of the maneuver after this one, when it comes soon after.
        val index = g.cues.indexOfFirst { it.distancePx > s }
        val upcoming = g.cues.getOrNull(index)
        val after = g.cues.getOrNull(index + 1)
        val gap = if (upcoming != null && after != null) {
            after.distancePx - upcoming.distancePx
        } else {
            null
        }
        val soon = gap != null && gap / k < THEN_WITHIN_STEPS && !turnAround
        thenStrip.visibility = if (soon && !arrived) View.VISIBLE else View.GONE
        if (soon) thenIcon.maneuver = after!!.maneuver
    }

    /**
     * The arrival-time format, looked up once: each lookup reads the 12/24-hour setting from
     * the system, 7-16 ms on the UI thread, and it ran on every footfall, so the map stalled a
     * few frames per step while walking (Pixel 7a, Nov 13).
     */
    private val arrivalFormat by lazy { DateFormat.getTimeFormat(this) }

    private fun renderEta(g: RouteGuide, s: Double, k: Double) {
        val stepsLeft = g.remainingSteps(s, k).coerceAtLeast(0)
        val meters = stepsLeft * stride
        val minutes = NavMath.walkingMinutes(meters)
        val arrival = Date(System.currentTimeMillis() + minutes * 60_000L)
        etaText?.update(getString(R.string.in_minutes, minutes))
        val clock = arrivalFormat.format(arrival)
        etaDetail?.update(
            resources.getQuantityString(
                R.plurals.in_eta_line,
                stepsLeft,
                NavMath.roundedMeters(meters),
                stepsLeft,
                clock,
            ),
        )
        renderDevLine(k)
    }

    /** The developer readout under the ETA: k, step counts and what the walking gate ignored. */
    private fun renderDevLine(k: Double) {
        val sensors = stats
        devLine?.update(devText(k, sensors))
    }

    private fun devText(k: Double, sensors: LiveStats?): String =
        if (liveMode && sensors != null) {
            getString(
                R.string.in_dev_live,
                k,
                sensors.steps,
                getString(
                    if (sensors.usingPeaks) R.string.source_peaks else R.string.source_detector,
                ),
                sensors.skipped,
                sensors.rejected,
            )
        } else if (liveMode) {
            getString(R.string.in_dev_live_waiting, k)
        } else {
            getString(R.string.in_dev_demo, getString(scenarioLabel(demoScenario)), k)
        }

    /**
     * Sets text only when it changed. A TextView re-lays out on every set, and the banner and
     * ETA are refreshed on every footfall.
     */
    private fun TextView.update(value: CharSequence?) {
        if (text?.toString() != value?.toString()) text = value
    }

    private fun renderNavigateSheet() {
        val end =
            roundIcon(R.drawable.ic_close, Ui.INK, Color.rgb(241, 243, 244), R.string.in_end) {
                stopNavigation()
            }
        val eta = text(Ui.HEADING, ETA_GREEN, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        val detail = text(Ui.CAPTION, Ui.INK_SECONDARY)
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(eta)
            addView(detail)
        }
        val action = if (liveMode) {
            roundIcon(R.drawable.ic_flag, Ui.ACCENT, Ui.ACCENT_SOFT, R.string.in_checkpoint) {
                beginCheckpoint()
            }
        } else {
            roundIcon(R.drawable.ic_pause, Ui.ACCENT, Ui.ACCENT_SOFT, R.string.in_pause) {
                setWalking(!walking)
            }
        }
        playButton = if (liveMode) null else action
        val sound = roundIcon(
            R.drawable.ic_volume_up,
            Ui.ACCENT,
            Ui.ACCENT_SOFT,
            R.string.in_voice_mute,
        ) { toggleVoice() }
        voiceButton = sound
        renderVoiceButton()
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(end)
            addView(middle, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sound, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) })
            addView(action)
        }
        val dev = text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0.8f
        }
        etaText = eta
        etaDetail = detail
        devLine = dev
        setSheet(row.withMargins(top = 4), dev.withMargins(top = 8, bottom = 4))
    }

    private fun toggleVoice() {
        voice.voiceOn = !voice.voiceOn
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_VOICE, voice.voiceOn)
            .apply()
        renderVoiceButton()
        showSnack(getString(if (voice.voiceOn) R.string.in_voice_on else R.string.in_voice_off))
    }

    private fun renderVoiceButton() {
        val button = voiceButton ?: return
        val on = voice.voiceOn
        button.setImageDrawable(
            icon(if (on) R.drawable.ic_volume_up else R.drawable.ic_volume_off, Ui.ACCENT),
        )
        button.contentDescription =
            getString(if (on) R.string.in_voice_mute else R.string.in_voice_unmute)
    }

    private fun arrive(pose: PoseEstimate) {
        closeSignScan()
        temi?.let { t ->
            t.onWalker(guide?.path?.lengthPx, pose.position)
            t.stop()
        }
        temi = null
        setWalking(false)
        following = false
        live.pause()
        logIo.execute { log?.arrive(pose) }
        // Arrived: hand the dot back from the walking model to the tracker's final position.
        map.driver = null
        puckMotion = null
        liveTrail = null
        map.setPuck(pose.position, durationMs = 400)
        show(Screen.ARRIVED)
        map.navigating = false
        map.setHeading(null)
        val p = place
        val steps = ((guide?.path?.lengthPx ?: 0.0) / pose.stepLengthPx).roundToInt()
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        val elapsed = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        val done = styledButton(ButtonStyle.PRIMARY, getString(R.string.in_done)).apply {
            setOnClickListener { clearPlace() }
        }
        val logged = logFile?.let {
            text(
                Ui.CAPTION,
                Ui.INK_SECONDARY
            ).apply { text = getString(R.string.log_saved, it.name) }
        }
        setSheet(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ImageView(
                        context
                    ).apply { setImageDrawable(icon(R.drawable.ic_check, ETA_GREEN, 32)) }
                )
                addView(
                    text(Ui.TITLE, bold = true).apply {
                        // An approximate end is beside the room, not at its door.
                        val near = p?.approximate == true
                        setText(if (near) R.string.in_arrived_near else R.string.in_arrived)
                        markHeading()
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) },
                )
            },
            text(Ui.HEADING).apply { text = p?.let { placeTitle(it) } }.withMargins(top = 8),
            *listOfNotNull(
                p?.takeIf { it.approximate }?.let {
                    text(Ui.BODY, Ui.INK_SECONDARY).apply {
                        setText(R.string.in_arrived_near_detail)
                    }.withMargins(top = 4)
                },
            ).toTypedArray(),
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                text = resources
                    .getQuantityString(R.plurals.in_arrived_detail, steps, steps, elapsed)
            }.withMargins(top = 2),
            *listOfNotNull(logged?.withMargins(top = 4)).toTypedArray(),
            done.withMargins(top = 16, bottom = 4),
        )
        map.fit(guide?.path?.points.orEmpty(), northUp = true)
    }

    private fun setWalking(on: Boolean) {
        val active = guide?.path
        walking = on && active != null && walk?.finished(active) == false
        ticker.removeCallbacks(tick)
        if (walking) ticker.post(tick)
        playButton?.let { button ->
            button.setImageDrawable(
                icon(if (walking) R.drawable.ic_pause else R.drawable.ic_play, Ui.ACCENT)
            )
            button.contentDescription =
                getString(if (walking) R.string.in_pause else R.string.in_play)
        }
    }

    // ---- Checkpoints (live) ----

    private fun beginCheckpoint() {
        show(Screen.CHECKPOINT)
        hintTitle.setText(R.string.in_checkpoint_title)
        hintBody.setText(R.string.in_checkpoint_body)
        map.flatten()
        map.onMapTap = { truth ->
            recordCheckpoint(truth)
            endCheckpoint()
        }
    }

    private fun endCheckpoint() {
        map.onMapTap = null
        show(Screen.NAVIGATE)
        map.startFollowing(IndoorMapView.Follow.HEADING)
    }

    private fun recordCheckpoint(truth: PixelPoint) {
        val g = graph ?: return
        val pose = session?.pose ?: return
        checkpoints += 1
        val result = g.checkpoint("C$checkpoints", pose, truth, stride, corridors)
        map.checkpoints = map.checkpoints + truth
        val now = SystemClock.elapsedRealtimeNanos()
        logIo.execute { log?.checkpoint(now, result) }
        val verdict =
            getString(if (result.sameCorridor) R.string.same_corridor else R.string.other_corridor)
        showSnack(getString(R.string.checkpoint_result, result.label, result.errorMeters, verdict))
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

    // ---- Live / demo mode ----

    private fun toggleMode() {
        if (liveMode) return setLiveMode(false)
        if (!live.hasHeadingSensor) return showSnack(getString(R.string.no_heading_sensor))
        if (Build.VERSION.SDK_INT >= 29 && !live.hasStepPermission) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), STEP_PERMISSION)
            return
        }
        setLiveMode(true)
    }

    private fun setLiveMode(on: Boolean) {
        liveMode = on
        // Warm the sensors early so heading has settled by the time the user faces a corridor.
        if (on) live.start() else live.stop()
        modeChip.text = getString(modeChipText(on))
        modeChip.contentDescription = getString(
            R.string.mode_description,
            modeChip.text,
            getString(if (on) R.string.mode_demo else R.string.mode_live),
        )
        if (screen == Screen.PREVIEW) renderPreviewSheet()
    }

    /**
     * Outside navigation the compass turns the map north up. While navigating it toggles
     * between the tilted heading-up view and a flat north-up view that still follows the user.
     */
    private fun toggleCompass() {
        if (screen != Screen.NAVIGATE) return map.resetNorth()
        recenter.visibility = View.GONE
        if (map.follow == IndoorMapView.Follow.HEADING) {
            map.resetNorth()
            compass.contentDescription = getString(R.string.in_compass_follow)
        } else {
            map.startFollowing(IndoorMapView.Follow.HEADING)
            compass.contentDescription = getString(R.string.in_compass)
        }
    }

    /** While navigating, offers the way back to the heading-up view the user just left. */
    private fun showRecenter() {
        if (screen != Screen.NAVIGATE || recenter.visibility == View.VISIBLE) return
        recenter.visibility = View.VISIBLE
        recenter.alpha = 0f
        recenter.animate().alpha(1f).setDuration(160).start()
    }

    // ---- Layout ----

    private fun buildLayout(): View {
        map = IndoorMapView(this).apply {
            contentDescription = getString(R.string.in_map_description_generic)
            onUserMoved = { showRecenter() }
            onRotationChanged = { deg ->
                compassNeedle.rotation = deg
                // While navigating the compass is the 2D / 3D toggle, so it never hides.
                val turned = kotlin.math.abs(deg) > 2f || screen == Screen.NAVIGATE
                val want = if (turned) View.VISIBLE else View.GONE
                if (compass.visibility != want) compass.visibility = want
            }
        }

        // Search bar: back, a tap target that opens search, and the Demo / Live chip.
        modeChip = text(Ui.CAPTION, Ui.ACCENT, bold = true).apply {
            setText(R.string.in_mode_chip_demo)
            gravity = Gravity.CENTER
            minWidth = dp(64)
            minHeight = dp(36)
            setPadding(dp(12), 0, dp(12), 0)
            background = pressable(rounded(Ui.ACCENT_SOFT, radiusDp = 18))
            setOnClickListener { toggleMode() }
        }
        searchBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Ui.SURFACE, radiusDp = 28)
            elevation = dpf(6f)
            setPadding(dp(4), 0, dp(10), 0)
            // One step back, like the system back gesture: an open place closes first, and
            // only the plain map leaves for Home (it used to leave from any screen).
            addView(
                backButton(Ui.INK) { backHandler.handleOnBackPressed() },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
            addView(
                text(Ui.BODY, Ui.INK_SECONDARY).apply {
                    searchLabel = this
                    setText(R.string.in_search_hint)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(56)
                    setStartIcon(R.drawable.ic_search, Ui.INK_SECONDARY)
                    compoundDrawablePadding = dp(12)
                    background = pressable(rounded(Color.TRANSPARENT, radiusDp = 24))
                    setOnClickListener { show(Screen.SEARCH) }
                },
                LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(4) },
            )
            addView(modeChip)
        }

        chips = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(10), 0, dp(6))
            addView(
                LinearLayout(context).apply {
                    listOf(
                        Triple(PlaceKind.EXIT, R.drawable.ic_exit, R.string.in_nearest_exit),
                        Triple(PlaceKind.ELEVATOR, R.drawable.ic_elevator, R.string.in_elevator),
                        Triple(PlaceKind.AED, R.drawable.ic_heart, R.string.in_aed),
                    ).forEach { (kind, iconId, label) ->
                        val view = chip(iconId, label) { openNearest(kind) }
                        // Hidden until the map loads and shows it has one of these.
                        view.visibility = View.GONE
                        featureChips[kind] = view
                        addView(
                            view,
                            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) },
                        )
                    }
                },
            )
        }

        // Route card: from / to, like a directions header.
        routeTo = text(Ui.BODY, bold = true)
        routeCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Ui.SURFACE, radiusDp = 20)
            elevation = dpf(6f)
            setPadding(dp(4), dp(10), dp(16), dp(10))
            addView(
                backButton(Ui.INK) {
                    backHandler.handleOnBackPressed()
                },
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        endpointRow(
                            dotIcon(IndoorMapView.PUCK_BLUE),
                            text(Ui.BODY).apply {
                                setText(R.string.in_your_location)
                            }
                        )
                    )
                    addView(
                        View(context).apply { setBackgroundColor(Ui.BORDER) },
                        LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                            topMargin = dp(6)
                            bottomMargin = dp(6)
                            marginStart = dp(32)
                        },
                    )
                    addView(
                        endpointRow(
                            ImageView(context).apply {
                                setImageDrawable(
                                    icon(R.drawable.ic_place, IndoorMapView.PIN_RED, 20),
                                )
                            },
                            routeTo
                        )
                    )
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(4) },
            )
        }

        // Turn-by-turn banner.
        bannerIcon = ManeuverView(this)
        bannerSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bannerDistance = text(Ui.BODY, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        bannerTitle = text(Ui.TITLE, Color.WHITE, bold = true).apply {
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        bannerDetail = text(Ui.BODY, Color.argb(225, 255, 255, 255)).apply { maxLines = 2 }
        thenIcon = ManeuverView(this)
        thenStrip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(BANNER_DARK, radiusDp = 14)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            addView(text(Ui.BODY, Color.WHITE, bold = true).apply { setText(R.string.in_then) })
            addView(thenIcon, square(24).apply { marginStart = dp(8) })
        }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(
                LinearLayout(context).apply {
                    bannerCard = this
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(BANNER_GREEN, radiusDp = 20)
                    elevation = dpf(6f)
                    setPadding(dp(16), dp(16), dp(20), dp(16))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            addView(bannerIcon, LinearLayout.LayoutParams(dp(56), dp(56)))
                            addView(bannerSpinner, LinearLayout.LayoutParams(dp(40), dp(40)))
                            addView(bannerDistance)
                        },
                        LinearLayout.LayoutParams(dp(72), WRAP),
                    )
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(bannerTitle)
                            addView(bannerDetail, wide().apply { topMargin = dp(2) })
                        },
                        LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) },
                    )
                },
            )
            addView(
                thenStrip,
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    topMargin = dp(6)
                    marginStart = dp(8)
                },
            )
        }

        confidenceDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ETA_GREEN)
            }
        }
        confidenceTitle = text(Ui.CAPTION, bold = true)
        confidenceDetail = text(Ui.CAPTION, CONF_LOW_INK).apply { visibility = View.GONE }
        escortTitle = text(Ui.BODY, bold = true)
        escortDetail = text(Ui.CAPTION, Ui.INK_SECONDARY)
        escortAction = text(Ui.BODY, Ui.ON_ACCENT, bold = true).apply {
            setText(R.string.temi_door_open)
            gravity = Gravity.CENTER
            minHeight = dp(Ui.TOUCH_DP)
            setPadding(dp(16), 0, dp(16), 0)
            background = pressable(rounded(Ui.ACCENT, radiusDp = 24))
            visibility = View.GONE
            setOnClickListener { temi?.doorOpened() }
        }
        escortCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            elevation = dpf(4f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(56)
            visibility = View.GONE
            background = rounded(Ui.SURFACE, radiusDp = 18)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(
                ImageView(context).apply {
                    setImageDrawable(icon(R.drawable.ic_robot, Color.WHITE, 22))
                    scaleType = ImageView.ScaleType.CENTER
                    background = rounded(IndoorMapView.ROBOT_TEAL, radiusDp = 18)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                square(36),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(escortTitle)
                    addView(escortDetail)
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) },
            )
            addView(
                escortAction,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
            )
        }
        confidenceAction = text(Ui.CAPTION, Ui.ON_ACCENT, bold = true).apply {
            setText(R.string.conf_scan_action)
            gravity = Gravity.CENTER
            minHeight = dp(32)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Ui.ACCENT, radiusDp = 16)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        confidenceChip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { openSignScan() }
            elevation = dpf(4f)
            setPadding(dp(14), dp(8), dp(16), dp(8))
            minimumHeight = dp(36)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(confidenceDot, square(10))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(confidenceTitle)
                    addView(confidenceDetail)
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) },
            )
            addView(
                confidenceAction,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) },
            )
        }

        hintTitle = text(Ui.HEADING, bold = true)
        hintBody = text(Ui.CAPTION, Ui.INK_SECONDARY)
        hintCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.SURFACE, radiusDp = 20)
            elevation = dpf(6f)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.GONE
            addView(hintTitle)
            addView(hintBody, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }

        compassNeedle = CompassView(this)
        compass = FrameLayout(this).apply {
            background = pressable(rounded(Ui.SURFACE, radiusDp = 24))
            elevation = dpf(4f)
            visibility = View.GONE
            contentDescription = getString(R.string.in_compass)
            isClickable = true
            setOnClickListener { toggleCompass() }
            addView(compassNeedle, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        val layers = fab(R.drawable.ic_layers, Ui.INK_SECONDARY, R.string.in_layers) {
            map.posterColors = !map.posterColors
            showSnack(
                getString(
                    if (map.posterColors) R.string.in_layer_poster else R.string.in_layer_plan
                )
            )
        }
        val sideButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(layers, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(compass, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(12) })
        }

        topArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(searchBar, LinearLayout.LayoutParams(MATCH, dp(56)))
            addView(chips)
            addView(routeCard)
            addView(banner)
            addView(escortCard, wide().apply { topMargin = dp(8) })
            addView(
                confidenceChip,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) },
            )
            addView(hintCard)
            addView(sideButtons, wide().apply { topMargin = dp(12) })
            padForSystemBars(bottom = false)
        }

        // Bottom: re-center and my-location above a white sheet.
        recenter = text(Ui.BODY, Ui.ACCENT, bold = true).apply {
            setText(R.string.in_recenter)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(Ui.TOUCH_DP)
            setPadding(dp(16), 0, dp(20), 0)
            background = pressable(rounded(Ui.SURFACE, radiusDp = 24))
            elevation = dpf(4f)
            setStartIcon(R.drawable.ic_navigation, Ui.ACCENT)
            compoundDrawablePadding = dp(8)
            visibility = View.GONE
            setOnClickListener {
                visibility = View.GONE
                map.startFollowing(IndoorMapView.Follow.HEADING)
            }
        }
        locate = fab(R.drawable.ic_my_location, Ui.ACCENT, R.string.in_my_location) {
            if (map.hasPuck) map.startFollowing(IndoorMapView.Follow.POSITION)
        }
        val floating = FrameLayout(this).apply {
            setPadding(dp(12), 0, dp(12), dp(12))
            clipToPadding = false
            addView(recenter, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.BOTTOM))
            addView(locate, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM))
        }
        snack = text(Ui.CAPTION, Color.WHITE).apply {
            background = rounded(Ui.INK, radiusDp = 8)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dpf(6f)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = topRounded(Ui.SURFACE, 24)
            elevation = dpf(12f)
            setPadding(dp(20), dp(8), dp(20), dp(16))
            padForSystemBars(top = false)
        }
        bottomArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            clipChildren = false
            addView(
                snack,
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                    bottomMargin = dp(8)
                },
            )
            addView(floating, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(sheet, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        searchPanel = buildSearchPanel()

        // Keeps the status bar readable where the map slides under it.
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(200, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.layoutParams = v.layoutParams.apply { height = top + dp(24) }
                insets
            }
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(IndoorMapView.BACKGROUND)
            addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(scrim, FrameLayout.LayoutParams(MATCH, 0, Gravity.TOP))
            addView(topArea, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
            addView(bottomArea, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            addView(searchPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        // The camera centres in the gap between the top cards and the sheet.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            map.setInsets(
                topArea.top + topCardsBottom(),
                root.height - (bottomArea.top + sheet.top)
            )
        }
        topArea.addOnLayoutChangeListener(relayout)
        bottomArea.addOnLayoutChangeListener(relayout)
        return root
    }

    /** Bottom of the last visible card in the top area; the side buttons float over the map. */
    private fun topCardsBottom(): Int {
        var bottom = topArea.paddingTop
        for (i in 0 until topArea.childCount - 1) {
            val c = topArea.getChildAt(i)
            if (c.visibility == View.VISIBLE) bottom = c.bottom
        }
        return bottom + dp(8)
    }

    private fun buildSearchPanel(): LinearLayout {
        searchField = EditText(this).apply {
            hint = getString(R.string.in_search_field)
            textSize = Ui.BODY
            setTextColor(Ui.INK)
            setHintTextColor(Ui.INK_SECONDARY)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) = Unit

                    override fun afterTextChanged(s: Editable?) =
                        renderResults(s?.toString().orEmpty())
                },
            )
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || enter) {
                    search?.search(text.toString())?.firstOrNull()?.let { openPlace(it.place) }
                    true
                } else {
                    false
                }
            }
        }
        searchClear = ImageButton(this).apply {
            setImageDrawable(icon(R.drawable.ic_close, Ui.INK_SECONDARY))
            background = pressable(rounded(Color.TRANSPARENT, radiusDp = 24))
            contentDescription = getString(R.string.in_clear)
            visibility = View.INVISIBLE
            setOnClickListener { searchField.setText("") }
        }
        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Ui.SURFACE, radiusDp = 28)
            elevation = dpf(2f)
            setPadding(dp(4), 0, dp(4), 0)
            addView(
                backButton(Ui.INK) {
                    backHandler.handleOnBackPressed()
                },
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
            addView(
                searchField,
                LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(4) }
            )
            addView(searchClear, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list = ScrollView(this).apply {
            clipToPadding = false
            addView(results)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            visibility = View.GONE
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(field, LinearLayout.LayoutParams(MATCH, dp(56)))
            addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
                )
                v.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, bars.bottom)
                insets
            }
        }
    }

    // ---- Small view helpers ----

    private fun handle(): View = View(this).apply {
        background = rounded(Ui.BORDER, radiusDp = 2)
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        }
    }

    private fun sectionLabel(id: Int) = text(Ui.CAPTION, Ui.INK_SECONDARY, bold = true).apply {
        setText(id)
        isAllCaps = true
        letterSpacing = 0.06f
        markHeading()
        setPadding(dp(20), dp(16), dp(20), dp(4))
    }

    private fun chip(iconId: Int, label: Int, onTap: () -> Unit) = text(
        Ui.CAPTION,
        Ui.INK,
        bold = true
    ).apply {
        setText(label)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(40)
        setPadding(dp(12), 0, dp(16), 0)
        background = pressable(rounded(Ui.SURFACE, radiusDp = 20))
        elevation = dpf(3f)
        setCompoundDrawablesRelative(icon(iconId, Ui.INK_SECONDARY, 18), null, null, null)
        compoundDrawablePadding = dp(8)
        setOnClickListener { onTap() }
    }

    private fun modeToggle(label: Int, selected: Boolean, onTap: () -> Unit) =
        text(Ui.CAPTION, if (selected) Ui.ACCENT else Ui.INK_SECONDARY, bold = true).apply {
            setText(label)
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(16), 0, dp(16), 0)
            background = pressable(
                if (selected) {
                    rounded(Ui.ACCENT_SOFT, radiusDp = 20)
                } else {
                    rounded(Ui.SURFACE, radiusDp = 20, stroke = Ui.BORDER)
                },
            )
            isSelected = selected
            setOnClickListener { onTap() }
        }

    private fun fab(iconId: Int, tint: Int, description: Int, onTap: () -> Unit) = ImageButton(
        this
    ).apply {
        setImageDrawable(icon(iconId, tint))
        background = pressable(rounded(Ui.SURFACE, radiusDp = 28))
        elevation = dpf(4f)
        contentDescription = getString(description)
        setOnClickListener { onTap() }
    }

    private fun roundIcon(iconId: Int, tint: Int, fill: Int, description: Int, onTap: () -> Unit) =
        ImageButton(this).apply {
            setImageDrawable(icon(iconId, tint))
            background = pressable(rounded(fill, radiusDp = 28))
            contentDescription = getString(description)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            setOnClickListener { onTap() }
        }

    private fun backButton(tint: Int, onTap: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(icon(R.drawable.ic_arrow_back, tint))
        background = pressable(rounded(Color.TRANSPARENT, radiusDp = 24))
        contentDescription = getString(R.string.nav_back)
        setOnClickListener { onTap() }
    }

    private fun iconBubble(iconId: Int, tint: Int, fill: Int) = ImageView(this).apply {
        setImageDrawable(icon(iconId, tint, 20))
        scaleType = ImageView.ScaleType.CENTER
        background = rounded(fill, radiusDp = 20)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dotIcon(color: Int) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun endpointRow(iconView: View, label: TextView) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val size = if (iconView is ImageView) 20 else 14
        addView(
            iconView,
            LinearLayout.LayoutParams(
                dp(size),
                dp(size)
            ).apply { marginStart = dp((20 - size) / 2 + 4) },
        )
        addView(
            label,
            LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(12 + (20 - size) / 2)
            }
        )
    }

    private fun topRounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        val r = dpf(radiusDp.toFloat())
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    private fun showSnack(message: String) {
        ticker.removeCallbacks(hideSnack)
        snack.text = message
        snack.animate().cancel()
        snack.visibility = View.VISIBLE
        snack.alpha = 0f
        snack.translationY = dpf(8f)
        snack.animate().alpha(1f).translationY(0f).setDuration(180).start()
        ticker.postDelayed(hideSnack, SNACK_MS)
    }

    private fun <T : View> T.withMargins(
        top: Int = 0,
        bottom: Int = 0,
        start: Int = 0,
        end: Int = 0,
    ): T {
        val params = layoutParams as? LinearLayout.LayoutParams
        layoutParams = (params ?: LinearLayout.LayoutParams(MATCH, WRAP)).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
            marginStart = dp(start)
            marginEnd = dp(end)
        }
        return this
    }

    // ---- Text ----

    private fun placeTitle(p: Place): String = when (p.kind) {
        PlaceKind.ROOM -> getString(R.string.in_room_title, p.label)
        PlaceKind.EXIT -> getString(R.string.in_exit_title)
        PlaceKind.ELEVATOR -> getString(R.string.in_elevator_title)
        PlaceKind.AED -> getString(R.string.in_aed_title)
    }

    private fun placeKind(p: Place): String {
        if (p.kind == PlaceKind.ROOM) {
            return if (p.approximate) {
                getString(R.string.in_room_approximate, roomKind())
            } else {
                roomKind()
            }
        }
        val near = graph?.nearestRoomLabel(p.position)
        val base = mapName ?: getString(R.string.in_feature_kind)
        return if (near == null) base else getString(R.string.in_near_room, base, near)
    }

    private fun roomKind(): String =
        mapName?.let { getString(R.string.in_room_kind_saved, it) }
            ?: getString(R.string.in_room_kind)

    private fun kindIcon(kind: PlaceKind) = when (kind) {
        PlaceKind.ROOM -> R.drawable.ic_door
        PlaceKind.EXIT -> R.drawable.ic_exit
        PlaceKind.ELEVATOR -> R.drawable.ic_elevator
        PlaceKind.AED -> R.drawable.ic_heart
    }

    private fun maneuverText(m: Maneuver, stepsUntil: Int): String = getString(
        when (m) {
            Maneuver.STRAIGHT -> R.string.in_go_straight
            Maneuver.TURN_LEFT -> R.string.in_turn_left
            Maneuver.TURN_RIGHT -> R.string.in_turn_right
            Maneuver.SLIGHT_LEFT -> R.string.in_bear_left
            Maneuver.SLIGHT_RIGHT -> R.string.in_bear_right
            Maneuver.U_TURN -> R.string.in_turn_around
            Maneuver.ARRIVE ->
                if (stepsUntil == 0) R.string.in_arrived else R.string.in_destination_ahead
        },
    )

    private fun pxToMeters(px: Double, k: Double) = px / k * stride

    private fun scenarioLabel(scenario: DemoScenario) = when (scenario) {
        DemoScenario.FOLLOW -> R.string.demo_follow
        DemoScenario.WRONG_TURN -> R.string.demo_wrong_turn
        DemoScenario.WRONG_WAY -> R.string.demo_wrong_way
    }

    /**
     * A feature shortcut. Exits and AEDs open the nearest one: in a hurry, the closest is the
     * answer. Elevators differ (banks serve different wings), so with more than one on the
     * map every elevator gets a pin and a row, and the user picks.
     */
    private fun openNearest(kind: PlaceKind) {
        val reachable = places.filter { it.kind == kind && walkPx[it.key] != null }
            .sortedBy { walkPx.getValue(it.key) }
        if (kind == PlaceKind.ELEVATOR && reachable.size > 1) return chooseAmong(reachable)
        search?.nearest(kind)?.let(::openPlace)
    }

    /** Pins every place in [choices] and lists them, nearest first; a pin or row opens one. */
    private fun chooseAmong(choices: List<Place>) {
        stopNavigation(toBrowse = false)
        place = null
        map.route = emptyList()
        clearRoomTint()
        map.pin = null
        map.pinLabel = null
        map.choicePins = choices.map { it.position }
        show(Screen.PLACE)
        map.fit(choices.map { it.position })
        setSheet(
            text(Ui.TITLE, bold = true).apply {
                text = getString(R.string.in_choose_elevator, choices.size)
                markHeading()
            },
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                setText(R.string.in_choose_elevator_body)
            }.withMargins(top = 2, bottom = 8),
            *choices.map { resultRow(PlaceMatch(it, null)) }.toTypedArray(),
        )
    }

    private fun square(sizeDp: Int) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun shown(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    private fun modeChipText(live: Boolean) =
        if (live) R.string.in_mode_chip_live else R.string.in_mode_chip_demo

    private fun TextView.setStartIcon(iconId: Int, tint: Int) {
        setCompoundDrawablesRelative(icon(iconId, tint, 20), null, null, null)
        compoundDrawablePadding = dp(12)
    }

    companion object {
        /** Id of a [SavedMap] to navigate on; without it the bundled EH3 map opens. */
        const val EXTRA_MAP_ID = "edu.wisc.maplens.MAP_ID"

        /** Set when the map was saved just now, to confirm it on arrival. */
        const val EXTRA_JUST_SAVED = "edu.wisc.maplens.JUST_SAVED"

        fun open(context: Context, mapId: String): Intent =
            Intent(context, IndoorNavActivity::class.java).putExtra(EXTRA_MAP_ID, mapId)

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val PERF_TAG = "MapLensPerf"

        /** Tap reach around an exit, elevator or AED, as a multiple of a door's. */
        private const val FEATURE_TAP_REACH = 2.5
        private const val SLOW_UI_MS = 16.0
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val STEP_PERMISSION = 8
        private const val CAMERA_PERMISSION = 9

        /** "Found room 3418" stays this long before the camera closes. */
        private const val SIGN_FOUND_MS = 900L

        /** Demo with temi: how long the walker stops halfway, and takes to open a door. */
        private const val DEMO_PAUSE_MS = 7_000L
        private const val DEMO_DOOR_MS = 3_000L
        private const val SNACK_MS = 3500L
        private const val THEN_WITHIN_STEPS = 25.0
        private const val SNAP_STEPS = 4.0
        private const val TAP_DOOR_DP = 10f
        private const val CORRIDOR_HALF_WIDTH_SHARE = 0.01
        private const val PREFS = "indoor_nav"
        private const val PREF_VOICE = "voice_on"

        private val ETA_GREEN = Color.rgb(24, 128, 56)
        private val BANNER_GREEN = Color.rgb(13, 101, 45)
        private val BANNER_DARK = Color.rgb(9, 74, 33)

        /** "Rerouting…": neutral dark grey, so it reads as a pause, not an instruction. */
        private val BANNER_GREY = Color.rgb(60, 64, 67)

        /** How long "Rerouting…" shows before the new route is swapped in. */
        private const val REROUTE_SHOW_MS = 900L

        /** With no way from here, wait this many steps before trying again. */
        private const val REROUTE_RETRY_STEPS = 8

        /** LOW confidence: amber dot, dark amber text on a soft amber chip. */
        private val CONF_LOW = Color.rgb(227, 116, 0)
        private val CONF_LOW_INK = Color.rgb(122, 62, 0)
        private val CONF_LOW_SOFT = Color.rgb(254, 243, 224)
        private val CONF_LOW_EDGE = Color.rgb(249, 203, 140)

        /** HIGH steps in a row before the chip goes back from uncertain to on track. */
        private const val HIGH_HOLD_STEPS = 3
    }
}
