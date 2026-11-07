package edu.wisc.maplens

import android.Manifest
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import edu.wisc.maplens.guidance.FakePoseStream
import edu.wisc.maplens.guidance.Instruction
import edu.wisc.maplens.guidance.Maneuver
import edu.wisc.maplens.guidance.RouteGuide
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
import edu.wisc.maplens.model.edgePoints
import edu.wisc.maplens.nav.CompassView
import edu.wisc.maplens.nav.IndoorMapView
import edu.wisc.maplens.nav.LabelBox
import edu.wisc.maplens.nav.ManeuverView
import edu.wisc.maplens.nav.MapRooms
import edu.wisc.maplens.nav.NavMath
import edu.wisc.maplens.nav.Place
import edu.wisc.maplens.nav.PlaceKind
import edu.wisc.maplens.nav.PlaceMatch
import edu.wisc.maplens.nav.PlaceSearch
import edu.wisc.maplens.nav.RoomArea
import edu.wisc.maplens.nav.RoomBounds
import edu.wisc.maplens.nav.RoomBox
import edu.wisc.maplens.nav.RoomFinder
import edu.wisc.maplens.nav.nearestRoomLabel
import edu.wisc.maplens.nav.places
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.routeToLabel
import edu.wisc.maplens.tracking.ParticleTracker
import edu.wisc.maplens.tracking.TrackingConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Indoor navigation on the bundled EH3 map, laid out like a phone maps app: search a room,
 * see it pinned, preview the walking route, then follow turn-by-turn guidance with a heading-up,
 * tilted map that follows a blue position arrow.
 *
 * It runs the same pipeline as [MainActivity] (which stays as it was): a fresh
 * [NavigationSession] per destination is the only writer of route and position, [RouteGuide]
 * turns poses into instructions, **Demo** walks the route with a [FakePoseStream], and **Live**
 * anchors heading at START from the corridor the user faces, then feeds steps and heading
 * through [LiveMotionSource] into [ParticleTracker]. Live also streams the aligned heading
 * between steps, so the arrow turns with the user in real time without GPS or Wi-Fi.
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
    private lateinit var modeChip: TextView
    private lateinit var chips: HorizontalScrollView
    private lateinit var routeCard: LinearLayout
    private lateinit var routeTo: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerIcon: ManeuverView
    private lateinit var bannerDistance: TextView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerDetail: TextView
    private lateinit var thenStrip: LinearLayout
    private lateinit var thenIcon: ManeuverView
    private lateinit var hintCard: LinearLayout
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

    private val loader = Executors.newSingleThreadExecutor()
    private val ticker = Handler(Looper.getMainLooper())
    private val stride = TrackingConfig().nominalStrideMeters
    private val router = AStarRouter()
    private val live by lazy { LiveMotionSource(this, ::onLivePose, onHeading = ::onLiveHeading) }
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
    private var roomBoxes: Map<String, RoomBox> = emptyMap()

    private var screen = Screen.BROWSE
    private var place: Place? = null
    private var session: NavigationSession? = null
    private var guide: RouteGuide? = null
    private var walk: FakePoseStream? = null
    private var walking = false
    private var liveMode = false
    private var following = false
    private var resumeFollowing = false
    private var stats: LiveStats? = null
    private var announcedCalibrationNs: Long? = null
    private var checkpoints = 0
    private var startedAt = 0L
    private var lastPose: PoseEstimate? = null
    private var stepsExpanded = false

    private val tick = object : Runnable {
        override fun run() {
            val stream = walk ?: return
            onPose(stream.step())
            if (walking && !stream.finished) ticker.postDelayed(this, Demo.STEP_INTERVAL_MS)
        }
    }

    private val hideSnack = Runnable {
        snack.animate().alpha(0f).translationY(dpf(8f)).setDuration(180).withEndAction {
            snack.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                }.onFailure { showSnack(getString(R.string.map_failed)) }
            }
        }
    }

    /** Graph, image, every searchable place and its walking distance from START. */
    private fun loadMap(): Pair<FloorGraph, android.graphics.Bitmap> {
        val g = MapAssets.loadGraph(this)
        val image = MapAssets.loadImage(this)
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
            search = PlaceSearch(all) { distances[it.key] }
            roomFinder = finder
        }
        // Reading the printed numbers takes a few seconds; taps work from doors until then.
        loader.execute {
            val boxes = MapRooms.labels(image, doors.keys, doors)
            // Each room's outline, wall to wall, grown once from its printed number.
            val rooms = RoomBounds.grow(walls, boxes)
            runOnUiThread {
                finder.labels = boxes
                labelBoxes = boxes.associateBy { it.label }
                roomBoxes = rooms
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
        setWalking(false)
        // Sensors stop with the screen, so live tracking re-anchors at START on return.
        resumeFollowing = following
        following = false
        live.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
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
        if (requestCode != STEP_PERMISSION) return
        if (!live.hasStepPermission) showSnack(getString(R.string.using_peaks))
        setLiveMode(true)
    }

    // ---- Screens ----

    private val backHandler = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
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
        chips.visibility = shown(next == Screen.BROWSE)
        routeCard.visibility = shown(next == Screen.PREVIEW)
        hintCard.visibility = shown(next == Screen.FACING || next == Screen.CHECKPOINT)
        locate.visibility = shown(!navLike && next != Screen.FACING)
        if (!navLike || next == Screen.CHECKPOINT) recenter.visibility = View.GONE
        setBannerVisible(next == Screen.NAVIGATE || next == Screen.ARRIVED)
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
                setText(R.string.in_building)
                markHeading()
            },
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply { setText(R.string.in_floor_line) }
                .withMargins(top = 4, bottom = 8),
        )
    }

    private fun clearPlace(moveCamera: Boolean = true) {
        stopNavigation(toBrowse = false)
        place = null
        map.pin = null
        map.pinLabel = null
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

    /** Selects [p]: pin on its printed number (or door), the room tinted, and its card. */
    private fun openPlace(p: Place, area: RoomArea? = null, moveCamera: Boolean = true) {
        stopNavigation(toBrowse = false)
        place = p
        val box = p.label?.let { labelBoxes[it] }
        val at = box?.center ?: p.position
        map.route = emptyList()
        showRoom(p.label, area ?: box?.let { roomFinder?.areaAround(it.center) })
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
        val finder = roomFinder ?: return
        val hit = finder.roomAt(tap, map.mapDistance(dpf(TAP_DOOR_DP)))
        val target = hit?.let { h -> places.firstOrNull { it.label == h.label } }
        when {
            target != null -> openPlace(target, hit.area, moveCamera = false)
            hit != null -> openUnmapped(hit.label, hit.area)
            screen == Screen.PLACE -> clearPlace(moveCamera = false)
        }
    }

    /**
     * Tints the room: its wall-to-wall outline when one was found, which covers the whole room
     * however much print is inside it, else the enclosed area around the tap.
     */
    private fun showRoom(label: String?, area: RoomArea?) {
        val outline = label?.let { roomBoxes[it] }
        map.highlightBox = outline
        map.highlight = if (outline == null) area else null
    }

    /** A printed room the traced graph has no door for: name it, but offer no route. */
    private fun openUnmapped(label: String, area: RoomArea?) {
        stopNavigation(toBrowse = false)
        place = null
        map.route = emptyList()
        showRoom(label, area)
        map.pin = labelBoxes[label]?.center
        map.pinLabel = label
        show(Screen.PLACE)
        setSheet(
            text(Ui.TITLE, bold = true).apply {
                text = getString(R.string.in_room_title, label)
                markHeading()
            },
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply { setText(R.string.in_room_kind) }
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
        next.onGraphVerified(g)
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
        if (liveMode) {
            walk = null
            askFacing(again = false)
            return
        }
        val stream = FakePoseStream(g.path, Demo.STEP_LENGTH_PX)
        walk = stream
        openLog("demo", p.label ?: p.key, g.path.bearingAt(0.0))
        startedAt = SystemClock.elapsedRealtime()
        show(Screen.NAVIGATE)
        renderNavigateSheet()
        val first = stream.current()
        map.setHeading(first.headingRad)
        onPose(first)
        map.startFollowing(IndoorMapView.Follow.HEADING)
        // Give the camera time to swing in before the walker sets off.
        ticker.postDelayed(
            { if (screen == Screen.NAVIGATE && walk === stream) setWalking(true) },
            900
        )
    }

    private fun stopNavigation(toBrowse: Boolean = true) {
        setWalking(false)
        following = false
        live.pause()
        walk = null
        map.facingChoices = emptyList()
        map.onFacingTap = null
        map.onMapTap = null
        map.navigating = false
        map.setHeading(null)
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
            map.startFollowing(IndoorMapView.Follow.HEADING)
        }
    }

    private fun onLiveHeading(mapRad: Double) {
        if (following) map.setHeading(mapRad)
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
        val s = session ?: return
        val g = guide ?: return
        s.onPose(pose)
        lastPose = pose
        logIo.execute { log?.pose(pose) }
        if (liveMode) {
            map.setPuck(pose.position, pose.spreadPx, durationMs = 450)
        } else {
            map.setPuck(pose.position, durationMs = Demo.STEP_INTERVAL_MS, linear = true)
            map.setHeading(pose.headingRad)
        }
        map.routeWalkedPx = g.progressPx(pose)
        val instruction = g.instruction(pose)
        renderBanner(g, pose, instruction)
        renderEta(g, pose)
        if (instruction.maneuver == Maneuver.ARRIVE && instruction.stepsUntil == 0 &&
            s.phase != Phase.ARRIVED
        ) {
            s.onArrived()
            arrive(pose)
        }
    }

    private fun renderBanner(g: RouteGuide, pose: PoseEstimate, instruction: Instruction) {
        val k = pose.stepLengthPx
        val arrived = instruction.maneuver == Maneuver.ARRIVE && instruction.stepsUntil == 0
        bannerIcon.maneuver = instruction.maneuver
        bannerTitle.text = maneuverText(instruction.maneuver, instruction.stepsUntil)
        val meters = NavMath.roundedMeters(instruction.stepsUntil * stride)
        bannerDistance.text = if (arrived) "" else getString(R.string.in_step_distance, meters)
        bannerDistance.visibility = if (arrived) View.GONE else View.VISIBLE
        bannerDetail.text = if (arrived) {
            place?.let { placeTitle(it) }
        } else {
            val n = instruction.stepsUntil
            resources.getQuantityString(R.plurals.in_banner_detail, n, instruction.detail, n)
        }
        banner.contentDescription = "${bannerTitle.text}, ${bannerDetail.text}"
        // "Then" preview of the maneuver after this one, when it comes soon after.
        val s = g.progressPx(pose)
        val index = g.cues.indexOfFirst { it.distancePx > s }
        val upcoming = g.cues.getOrNull(index)
        val after = g.cues.getOrNull(index + 1)
        val gap = if (upcoming != null && after != null) {
            after.distancePx - upcoming.distancePx
        } else {
            null
        }
        val soon = gap != null && gap / k < THEN_WITHIN_STEPS
        thenStrip.visibility = if (soon && !arrived) View.VISIBLE else View.GONE
        if (soon) thenIcon.maneuver = after!!.maneuver
    }

    private fun renderEta(g: RouteGuide, pose: PoseEstimate) {
        val stepsLeft = g.remainingSteps(pose).coerceAtLeast(0)
        val meters = stepsLeft * stride
        val minutes = NavMath.walkingMinutes(meters)
        val arrival = Date(System.currentTimeMillis() + minutes * 60_000L)
        etaText?.text = getString(R.string.in_minutes, minutes)
        etaDetail?.text = resources.getQuantityString(
            R.plurals.in_eta_line,
            stepsLeft,
            NavMath.roundedMeters(meters),
            stepsLeft,
            DateFormat.getTimeFormat(this).format(arrival),
        )
        val sensors = stats
        devLine?.text = if (liveMode && sensors != null) {
            getString(
                R.string.in_dev_live,
                pose.stepLengthPx,
                sensors.steps,
                getString(
                    if (sensors.usingPeaks) R.string.source_peaks else R.string.source_detector,
                ),
                sensors.skipped,
            )
        } else if (liveMode) {
            getString(R.string.in_dev_live_waiting, pose.stepLengthPx)
        } else {
            getString(R.string.in_dev_demo, pose.stepLengthPx)
        }
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
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(end)
            addView(middle, LinearLayout.LayoutParams(0, WRAP, 1f))
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

    private fun arrive(pose: PoseEstimate) {
        setWalking(false)
        following = false
        live.pause()
        logIo.execute { log?.arrive(pose) }
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
                        setText(R.string.in_arrived)
                        markHeading()
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) },
                )
            },
            text(Ui.HEADING).apply { text = p?.let { placeTitle(it) } }.withMargins(top = 8),
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
        walking = on && walk?.finished == false
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
            contentDescription = getString(R.string.in_map_description)
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
            addView(backButton(Ui.INK) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(
                text(Ui.BODY, Ui.INK_SECONDARY).apply {
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
                    addView(
                        chip(R.drawable.ic_exit, R.string.in_nearest_exit) {
                            search?.nearest(PlaceKind.EXIT)?.let(::openPlace)
                        }
                    )
                    addView(
                        chip(R.drawable.ic_elevator, R.string.in_elevator) {
                            search?.nearest(PlaceKind.ELEVATOR)?.let(::openPlace)
                        },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
                    )
                    addView(
                        chip(R.drawable.ic_heart, R.string.in_aed) { openNearest(PlaceKind.AED) },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
                    )
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
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(BANNER_GREEN, radiusDp = 20)
                    elevation = dpf(6f)
                    setPadding(dp(16), dp(16), dp(20), dp(16))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            addView(bannerIcon, LinearLayout.LayoutParams(dp(56), dp(56)))
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
        if (p.kind == PlaceKind.ROOM) return getString(R.string.in_room_kind)
        val near = graph?.nearestRoomLabel(p.position)
        val base = getString(R.string.in_feature_kind)
        return if (near == null) base else getString(R.string.in_near_room, base, near)
    }

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

    private fun openNearest(kind: PlaceKind) {
        search?.nearest(kind)?.let(::openPlace)
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

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val STEP_PERMISSION = 8
        const val SNACK_MS = 3500L
        const val THEN_WITHIN_STEPS = 25.0
        const val TAP_DOOR_DP = 10f
        const val CORRIDOR_HALF_WIDTH_SHARE = 0.01

        val ETA_GREEN = Color.rgb(24, 128, 56)
        val BANNER_GREEN = Color.rgb(13, 101, 45)
        val BANNER_DARK = Color.rgb(9, 74, 33)
    }
}
