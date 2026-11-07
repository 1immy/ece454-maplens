package edu.wisc.maplens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import edu.wisc.maplens.mapparse.RoomPreview
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.nearestEdge
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.roomDestinations
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Screen tolerance for a finger: half of a 48 dp target on each side. */
private const val FINGER_DP = 24f

private const val DOT_OVER_LABEL_DP = 8f

/** Past this zoom a closer look can't separate points, so a tap asks which one instead. */
private const val CHOOSE_ZOOM = 2.9f

/** The map key is the one place below the type scale: it labels colors and stays out of the way. */
private const val LEGEND_SP = 12f

/**
 * "Check the map": fix a draft graph, then save it as verified. Works on the draft in [DraftStore],
 * so edits outlast this screen. The panel under the map always holds what can be done next.
 */
class GraphEditorActivity : ComponentActivity() {
    private sealed interface Goal {
        data object AddPoint : Goal
        data object AddDoor : Goal
        data object SetStart : Goal
        data class MovePoint(val id: String) : Goal
        data class RoomDoor(val index: Int) : Goal
    }

    private sealed interface Typing {
        data class PointLabel(val id: String, val old: String?) : Typing
        data class NewDoor(val at: PixelPoint) : Typing
        data class RoomNumber(val index: Int) : Typing
    }

    private sealed interface Panel {
        data object Idle : Panel
        data class Point(val id: String) : Panel
        data class Corridor(val edgeId: String, val at: PixelPoint) : Panel
        data class Place(val goal: Goal, val back: Panel) : Panel
        data class LinkFrom(val id: String) : Panel
        data class Choose(val ids: List<String>, val linkFrom: String?) : Panel
        data class Type(val typing: Typing, val back: Panel) : Panel
        data object Problems : Panel
        data object Confirm : Panel
        data object Rooms : Panel
        data class Room(val index: Int) : Panel
    }

    private lateinit var map: MapView
    private lateinit var hint: TextView
    private lateinit var undo: View
    private lateinit var fullMap: Button
    private lateinit var panelView: LinearLayout
    private lateinit var roomPanel: PosterReviewPanel
    private var placeButton: Button? = null
    private var image: Bitmap? = null
    private lateinit var history: EditHistory<MapDraft>
    private var panel: Panel = Panel.Idle
    private var shownRooms: List<ReviewRoom> = emptyList()
    private lateinit var legend: TextView

    /** Set when moving to the next room, so a zoomed map goes along with it. */
    private var followRoom = false

    /** Working through the room list. A room tapped on the map is a one-off fix instead. */
    private var checkingList = true
    private val drafts by lazy { DraftStore.of(this) }
    private val worker = Executors.newSingleThreadExecutor()

    private val graph: FloorGraph get() = history.current.graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goBack()
            },
        )
        hint.setHint(getString(R.string.loading_map), false)
        worker.execute {
            val loaded = runCatching {
                val photo = checkNotNull(BitmapFactory.decodeFile(drafts.image.path))
                drafts.load() to photo
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loaded.onSuccess { (draft, photo) ->
                    history = EditHistory(draft)
                    image = photo
                    map.review = draft.graph
                    map.editor = EditorMarks()
                    map.bitmap = photo
                    render()
                }.onFailure {
                    Log.w(TAG, "Could not open the draft", it)
                    hint.setHint(getString(R.string.editor_open_failed), true)
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        undo = iconButton(R.drawable.ic_undo, getString(R.string.editor_undo)) {
            history.undo()
            persist()
            panel = Panel.Idle
            render(getString(R.string.editor_undone))
        }.apply { isEnabled = false }
        val back = iconButton(R.drawable.ic_arrow_back, getString(R.string.nav_back)) {
            onBackPressedDispatcher.onBackPressed()
        }
        val topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(back, LinearLayout.LayoutParams(dp(Ui.TOUCH_DP), dp(Ui.TOUCH_DP)))
            addView(
                text(Ui.HEADING, bold = true).apply {
                    setText(R.string.poster_review)
                    markHeading()
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) },
            )
            addView(undo, LinearLayout.LayoutParams(dp(Ui.TOUCH_DP), dp(Ui.TOUCH_DP)))
        }
        hint = text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
            setPadding(dp(16), 0, dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        map = MapView(this).apply {
            contentDescription = getString(R.string.poster_map_description)
            setBackgroundColor(Ui.BACKGROUND)
            onMapTap = ::onTap
            onViewChange = { updateMapChrome() }
        }
        fullMap = styledButton(ButtonStyle.TONAL).apply {
            textSize = Ui.CAPTION
            isSingleLine = true
            visibility = View.GONE
            setOnClickListener {
                val room = map.selectedRoom
                if (map.isZoomed || room == null) {
                    map.showAll()
                } else {
                    map.focus(room.preview.bounds)
                }
            }
        }
        // Not clickable, so a tap on it still reaches the map underneath.
        legend = text(LEGEND_SP, Ui.INK).apply {
            background = rounded(Ui.SURFACE, radiusDp = 8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val mapArea = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(
                fullMap,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                },
            )
            addView(
                legend,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                },
            )
        }
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(12), dp(8), dp(12), dp(12))
            // The same height for the everyday panels, so the map doesn't shift under a finger.
            minimumHeight = dp(144)
        }
        roomPanel = PosterReviewPanel(this).apply {
            onPick = { room ->
                val index = room?.let { shownRooms.indexOf(it) }?.let(::indexOfRoom)
                if (index != null) {
                    panel = Panel.Room(index)
                    render()
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BACKGROUND)
            addView(topBar)
            addView(hint)
            addView(mapArea, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(
                View(context).apply { setBackgroundColor(Ui.BORDER) },
                LinearLayout.LayoutParams(-1, dp(1)),
            )
            addView(panelView, LinearLayout.LayoutParams(-1, -2))
            padForSystemBars()
        }
    }

    /** Back steps out one level at a time, then to the full map, and only then leaves. */
    private fun goBack() {
        if (!::history.isInitialized) return finish()
        when (val current = panel) {
            Panel.Idle -> if (map.isZoomed) map.showAll() else finish()
            is Panel.Place -> open(current.back)
            is Panel.Type -> open(current.back)
            is Panel.Room -> open(roomsOrIdle())
            is Panel.LinkFrom -> open(Panel.Point(current.id))
            is Panel.Choose -> open(
                current.linkFrom?.let { Panel.LinkFrom(it) } ?: Panel.Idle,
            )
            else -> open(Panel.Idle)
        }
    }

    private fun open(next: Panel) {
        panel = next
        render()
    }

    /** Makes one edit. A refused edit explains itself and changes nothing. */
    private fun edit(
        done: Int,
        next: (MapDraft) -> Panel = { Panel.Idle },
        change: (MapDraft) -> MapDraft
    ) {
        val changed = try {
            change(history.current)
        } catch (refused: IllegalArgumentException) {
            render(refused.message, warn = true)
            return
        }
        history.push(changed)
        persist()
        panel = next(changed)
        render(getString(done))
    }

    private fun persist() {
        val snapshot = history.current
        worker.execute {
            runCatching { drafts.update(snapshot) }.onFailure {
                Log.w(TAG, "Draft not written", it)
                runOnUiThread { render(getString(R.string.editor_keep_failed), warn = true) }
            }
        }
    }

    private fun onTap(tap: PixelPoint) {
        when (val current = panel) {
            is Panel.Place -> Unit
            Panel.Rooms, is Panel.Room -> pickRoom(tap)
            is Panel.LinkFrom -> when (val pick = graph.pickAt(tap, fingerPx())) {
                is Pick.Points -> pickPoints(pick.ids, current.id, tap)
                else -> render(getString(R.string.editor_link_pick_point), warn = true)
            }
            else -> {
                val index = map.roomNear(tap)?.let { indexOfRoom(shownRooms.indexOf(it)) }
                // A dot right under the finger still wins over the label box around it.
                val onDot = graph.pickAt(tap, map.mapPixels(DOT_OVER_LABEL_DP)) is Pick.Points
                if (index != null && !onDot) {
                    checkingList = false
                    return open(Panel.Room(index))
                }
                when (val pick = graph.pickAt(tap, fingerPx())) {
                    is Pick.Points -> pickPoints(pick.ids, null, tap)
                    is Pick.Corridor -> open(Panel.Corridor(pick.edgeId, tap))
                    Pick.Nothing -> open(Panel.Idle)
                }
            }
        }
    }

    private fun pickRoom(tap: PixelPoint) {
        val room = map.roomNear(tap)
        val index = room?.let { shownRooms.indexOf(it) }?.let(::indexOfRoom)
        open(if (index == null || room == map.selectedRoom) Panel.Rooms else Panel.Room(index))
    }

    private fun pickPoints(ids: List<String>, linkFrom: String?, tap: PixelPoint) {
        val others = ids.filter { it != linkFrom }
        when {
            others.size == 1 -> onPointChosen(others.single(), linkFrom)
            others.isEmpty() -> render(getString(R.string.editor_link_pick_point), warn = true)
            map.zoomLevel < CHOOSE_ZOOM -> {
                map.focus(others.mapNotNull { graph.nodes[it]?.position } + tap)
                render(getString(R.string.editor_tap_again))
            }
            else -> open(Panel.Choose(others, linkFrom))
        }
    }

    private fun onPointChosen(id: String, linkFrom: String?) {
        if (linkFrom == null) return open(Panel.Point(id))
        edit(
            R.string.editor_linked,
            { Panel.Point(id) }
        ) { it.withGraph(it.graph.connect(linkFrom, id)) }
    }

    private fun fingerPx() = map.mapPixels(FINGER_DP)

    private fun render(message: String? = null, warn: Boolean = false) {
        val problems = mapProblems(graph)
        val blocking = problems.filter { it.blocksSave }
        placeButton = null
        map.review = graph
        shownRooms = reviewedRooms()
        // What the parser read stays on the map throughout, so a wrong number is easy to spot.
        map.reviewRooms = shownRooms
        map.selectedRoom = (panel as? Panel.Room)?.let { current ->
            val preview = history.current.rooms[current.index].toPreview()
            shownRooms.firstOrNull { it.preview == preview }
        }
        if (followRoom && map.isZoomed) map.selectedRoom?.let { map.focus(it.preview.bounds) }
        followRoom = false
        legend.text = legendText()
        // The map is short while typing, and number badges can land where the key sits.
        val mapBusy = panel is Panel.Type || panel is Panel.Choose
        legend.visibility = if (mapBusy) View.GONE else View.VISIBLE
        undo.isEnabled = history.canUndo
        undo.alpha = if (history.canUndo) 1f else 0.35f
        panelView.removeAllViews()
        when (val current = panel) {
            Panel.Idle -> showIdle(blocking.size)
            is Panel.Point -> showPoint(current.id)
            is Panel.Corridor -> showCorridor(current)
            is Panel.Place -> showPlace(current)
            is Panel.LinkFrom -> showLink(current.id)
            is Panel.Choose -> showChoose(current)
            is Panel.Type -> showTyping(current)
            Panel.Problems -> showProblems(problems)
            Panel.Confirm -> showConfirm(problems)
            Panel.Rooms -> showRooms()
            is Panel.Room -> showRoom(current.index)
        }
        if (panel !is Panel.Type) hideKeyboard()
        hint.setHint(message ?: defaultHint(), warn)
        updateMapChrome(keepHint = message != null)
    }

    /** What the colors on the map mean. */
    private fun legendText(): CharSequence = SpannableStringBuilder().apply {
        for ((color, label) in listOf(
            REVIEW_CHECK to R.string.poster_legend_check,
            REVIEW_ROOM to R.string.poster_legend_room,
            REVIEW_CORRIDOR to R.string.editor_legend_corridor,
        )) {
            if (isNotEmpty()) append("   ")
            val from = length
            append("● ")
            setSpan(ForegroundColorSpan(color), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(getString(label))
        }
    }

    private fun defaultHint() = getString(
        when (panel) {
            Panel.Idle -> R.string.editor_hint_idle
            is Panel.Place -> R.string.editor_hint_place
            is Panel.LinkFrom -> R.string.editor_hint_link
            is Panel.Choose -> R.string.editor_hint_choose
            Panel.Rooms -> R.string.editor_hint_rooms
            is Panel.Type -> R.string.editor_hint_type
            is Panel.Room -> R.string.editor_hint_room
            Panel.Problems -> R.string.editor_hint_problems
            Panel.Confirm -> R.string.editor_hint_confirm
            else -> R.string.editor_hint_selected
        },
    )

    /** Keeps the marks and the map button in step with the panel and the view. */
    private fun updateMapChrome(keepHint: Boolean = false) {
        if (!::history.isInitialized) return
        val place = panel as? Panel.Place
        val snap = place?.let(::snapFor)
        map.editor = EditorMarks(
            selectedPoint = when (val current = panel) {
                is Panel.Point -> current.id
                is Panel.LinkFrom -> current.id
                else -> null
            },
            selectedCorridor = (panel as? Panel.Corridor)?.edgeId,
            problems = mapProblems(graph).filter { it.blocksSave }.mapNotNull { it.at },
            crosshair = place != null,
            snap = snap,
            choices = (panel as? Panel.Choose)?.ids.orEmpty(),
        )
        placeButton?.isEnabled = place == null || snap != null || !needsCorridor(place.goal)
        if (place != null && !keepHint) {
            val hintId = when {
                snap != null || place.goal == Goal.SetStart || place.goal is Goal.MovePoint ->
                    R.string.editor_hint_place
                needsCorridor(place.goal) -> R.string.editor_hint_off_corridor
                else -> R.string.editor_hint_free_point
            }
            hint.setHint(getString(hintId), false)
        }
        val room = map.selectedRoom
        when {
            map.isZoomed -> fullMap.setText(R.string.poster_full_map)
            room != null -> fullMap.text = getString(R.string.poster_zoom_to, room.preview.label)
            else -> {
                fullMap.visibility = View.GONE
                return
            }
        }
        fullMap.visibility = View.VISIBLE
    }

    private fun needsCorridor(goal: Goal) = goal is Goal.AddDoor || goal is Goal.RoomDoor

    private fun snapFor(place: Panel.Place): PixelPoint? {
        if (place.goal is Goal.MovePoint) return null
        val hit = graph.nearestEdge(map.center()) ?: return null
        return hit.point.takeIf { place.goal == Goal.SetStart || hit.distancePx <= fingerPx() }
    }

    private fun showIdle(blocking: Int) {
        val toCheck = reviewedRooms().count { it.check != RoomCheck.CLEAR }
        val rooms = history.current.rooms.size
        panelView.addView(
            toolRow(
                listOf(
                    Tool(R.drawable.ic_add, getString(R.string.editor_add_point)) {
                        startPlacing(Goal.AddPoint, map.center())
                    },
                    Tool(R.drawable.ic_door, getString(R.string.editor_add_door)) {
                        startPlacing(Goal.AddDoor, map.center())
                    },
                    Tool(R.drawable.ic_flag, getString(R.string.editor_set_start)) {
                        val at = graph.nodes[graph.startNodeId]?.position ?: map.center()
                        startPlacing(Goal.SetStart, at)
                    },
                    Tool(
                        R.drawable.ic_list,
                        if (toCheck > 0) {
                            getString(R.string.editor_rooms_to_check, toCheck)
                        } else {
                            getString(R.string.editor_rooms, rooms)
                        },
                    ) { open(Panel.Rooms) },
                ),
            ),
        )
        val save = if (blocking == 0) {
            styledButton(ButtonStyle.PRIMARY, getString(R.string.editor_save)).apply {
                setOnClickListener { open(Panel.Confirm) }
            }
        } else {
            styledButton(
                ButtonStyle.SECONDARY,
                resources.getQuantityString(R.plurals.editor_save_blocked, blocking, blocking),
            ).apply {
                setTextColor(REVIEW_CHECK)
                setOnClickListener { open(Panel.Problems) }
            }
        }
        panelView.addView(save, spaced(top = 8))
    }

    private fun showPoint(id: String) {
        val node = graph.nodes[id] ?: return open(Panel.Idle)
        val draft = history.current
        val readings = draft.roomsAt(id)
        val single = readings.singleOrNull()
        if (single != null) {
            val title = getString(R.string.editor_room_title, draft.rooms[single].label)
            panelView.addView(readingHeader(draft.rooms[single], title) { open(Panel.Idle) })
        } else {
            panelView.addView(panelTitle(pointName(id)) { open(Panel.Idle) })
            if (readings.isNotEmpty()) {
                // Two rooms on one door: each opens on its own, with its own photo.
                panelView.addView(
                    spacedRows(
                        readings.map { index ->
                            val title =
                                getString(R.string.editor_room_title, draft.rooms[index].label)
                            listRow(title) {
                                checkingList = false
                                open(Panel.Room(index))
                            }
                        },
                        maxPx = resources.displayMetrics.heightPixels * 25 / 100,
                    ),
                )
            }
        }
        val isStart = node.type == NodeType.START
        panelView.addView(
            toolRow(
                buildList {
                    add(
                        Tool(R.drawable.ic_move, getString(R.string.editor_move)) {
                            startPlacing(Goal.MovePoint(id), node.position)
                        },
                    )
                    if (!isStart) {
                        add(
                            Tool(R.drawable.ic_edit, getString(R.string.editor_number)) {
                                val old = graph.landmarks.firstOrNull { it.nodeId == id }?.label
                                val typing = single?.let { Typing.RoomNumber(it) }
                                    ?: Typing.PointLabel(id, old)
                                open(Panel.Type(typing, panel))
                            },
                        )
                    }
                    add(
                        Tool(R.drawable.ic_link, getString(R.string.editor_link)) {
                            open(Panel.LinkFrom(id))
                        },
                    )
                    if (!isStart) {
                        add(
                            Tool(R.drawable.ic_flag, getString(R.string.editor_set_start)) {
                                edit(R.string.editor_start_set) { draft ->
                                    draft.withGraph(draft.graph.setStart(node.position).first)
                                }
                            },
                        )
                    }
                    add(
                        Tool(R.drawable.ic_delete, getString(R.string.editor_delete)) {
                            edit(R.string.editor_deleted) { it.withGraph(it.graph.deleteNode(id)) }
                        },
                    )
                },
            ),
        )
    }

    private fun showCorridor(current: Panel.Corridor) {
        panelView.addView(panelTitle(getString(R.string.editor_corridor)) { open(Panel.Idle) })
        panelView.addView(
            toolRow(
                listOf(
                    Tool(R.drawable.ic_add, getString(R.string.editor_add_point)) {
                        startPlacing(Goal.AddPoint, current.at)
                    },
                    Tool(R.drawable.ic_door, getString(R.string.editor_add_door)) {
                        startPlacing(Goal.AddDoor, current.at)
                    },
                    Tool(R.drawable.ic_delete, getString(R.string.editor_delete)) {
                        edit(R.string.editor_deleted) {
                            it.withGraph(it.graph.deleteEdge(current.edgeId))
                        }
                    },
                ),
            ),
        )
    }

    private fun startPlacing(goal: Goal, at: PixelPoint) {
        panel = Panel.Place(goal, panel)
        render()
        map.centerOn(at)
    }

    private fun showPlace(current: Panel.Place) {
        val titleId = when (current.goal) {
            Goal.AddPoint -> R.string.editor_place_point
            Goal.AddDoor -> R.string.editor_place_door
            Goal.SetStart -> R.string.editor_place_start
            is Goal.MovePoint -> R.string.editor_place_move
            is Goal.RoomDoor -> R.string.editor_place_door
        }
        panelView.addView(panelTitle(getString(titleId)))
        val cancel = styledButton(ButtonStyle.SECONDARY, getString(android.R.string.cancel)).apply {
            setOnClickListener { goBack() }
        }
        val place = styledButton(ButtonStyle.PRIMARY, getString(R.string.editor_place_here)).apply {
            setOnClickListener { place(current.goal, current.back) }
        }
        placeButton = place
        panelView.addView(pair(cancel, place))
    }

    private fun place(goal: Goal, back: Panel) {
        val at = map.center()
        val snapPx = fingerPx()
        when (goal) {
            Goal.AddPoint -> {
                var id = ""
                edit(R.string.editor_point_added, { Panel.Point(id) }) { draft ->
                    val (next, added) = draft.graph.addPoint(at, snapPx)
                    id = added
                    draft.withGraph(next)
                }
            }
            Goal.AddDoor -> open(Panel.Type(Typing.NewDoor(at), back))
            Goal.SetStart -> edit(R.string.editor_start_set) {
                it.withGraph(it.graph.setStart(at).first)
            }
            is Goal.MovePoint -> edit(R.string.editor_moved, { Panel.Point(goal.id) }) {
                it.withGraph(it.graph.moveNode(goal.id, at))
            }
            is Goal.RoomDoor -> edit(
                R.string.editor_door_placed,
                afterFix(goal.index, history.current.checkOrder(goal.index)),
            ) {
                it.placeRoomDoor(goal.index, at, snapPx)
            }
        }
    }

    private fun showLink(id: String) {
        panelView.addView(panelTitle(getString(R.string.editor_linking, pointName(id))))
        panelView.addView(
            styledButton(ButtonStyle.SECONDARY, getString(android.R.string.cancel)).apply {
                setOnClickListener { goBack() }
            },
        )
    }

    private fun showChoose(current: Panel.Choose) {
        panelView.addView(panelTitle(getString(R.string.editor_which)) { goBack() })
        panelView.addView(
            spacedRows(
                current.ids.mapIndexed { i, id ->
                    listRow("${i + 1}.  ${pointName(id)}") { onPointChosen(id, current.linkFrom) }
                },
                maxPx = resources.displayMetrics.heightPixels * 35 / 100,
            ),
        )
    }

    private fun showTyping(current: Panel.Type) {
        val old = when (val typing = current.typing) {
            is Typing.PointLabel -> typing.old
            is Typing.NewDoor -> null
            is Typing.RoomNumber -> history.current.rooms[typing.index].label
        }
        val field = EditText(this).apply {
            setText(old.orEmpty())
            setSelection(text.length)
            hint = getString(R.string.editor_number_hint)
            contentDescription = getString(R.string.editor_number_hint)
            textSize = Ui.HEADING
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = dp(Ui.TOUCH_DP + 4)
            background = rounded(Ui.BACKGROUND, radiusDp = 12, stroke = Ui.BORDER)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val confirm = { commitTyping(current.typing, field.text.toString(), current.back) }
        field.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) confirm() }
        }
        val reading = (current.typing as? Typing.RoomNumber)
            ?.let { history.current.rooms[it.index] }
        val title = if (old == null) {
            getString(R.string.editor_number_title)
        } else {
            getString(R.string.editor_number_read, old)
        }
        panelView.addView(
            if (reading != null) readingHeader(reading, title, null) else panelTitle(title),
        )
        panelView.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val cancel = styledButton(ButtonStyle.SECONDARY, getString(android.R.string.cancel)).apply {
            setOnClickListener { goBack() }
        }
        val done = styledButton(ButtonStyle.PRIMARY, getString(R.string.editor_done)).apply {
            setOnClickListener { confirm() }
        }
        panelView.addView(pair(cancel, done), spaced(top = 8))
        field.requestFocus()
        field.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun commitTyping(typing: Typing, value: String, back: Panel) {
        when (typing) {
            is Typing.PointLabel -> edit(R.string.editor_number_saved, { back }) {
                it.labelPoint(typing.id, typing.old, value)
            }
            is Typing.NewDoor -> {
                var id = ""
                edit(R.string.editor_door_placed, { Panel.Point(id) }) { draft ->
                    val (next, door) = draft.graph.placeDoor(value, typing.at, fingerPx())
                    id = door
                    draft.withGraph(next)
                }
            }
            is Typing.RoomNumber -> edit(
                fixedMessage(history.current.rooms[typing.index]),
                afterFix(typing.index, history.current.checkOrder(typing.index)),
            ) {
                it.renameRoom(typing.index, value)
            }
        }
    }

    private fun showProblems(problems: List<MapProblem>) {
        val blocking = problems.filter { it.blocksSave }
        if (blocking.isEmpty()) return open(Panel.Idle)
        panelView.addView(
            panelTitle(
                resources.getQuantityString(
                    R.plurals.editor_problems_title,
                    blocking.size,
                    blocking.size,
                ),
            ) { open(Panel.Idle) },
        )
        panelView.addView(
            spacedRows(
                blocking.map { problem ->
                    val fix = problem.crossing?.let { crossing ->
                        styledButton(
                            ButtonStyle.TONAL,
                            getString(R.string.editor_add_junction)
                        ).apply {
                            textSize = Ui.CAPTION
                            setOnClickListener {
                                edit(R.string.editor_point_added, { Panel.Problems }) { draft ->
                                    draft.withGraph(draft.graph.joinAt(crossing).first)
                                }
                            }
                        }
                    }
                    listRow(problem.message, fix) {
                        panel = Panel.Idle
                        problem.at?.let { map.focus(listOf(it)) }
                        render(problem.message, warn = true)
                    }
                },
                maxPx = resources.displayMetrics.heightPixels * 35 / 100,
            ),
        )
    }

    private fun showConfirm(problems: List<MapProblem>) {
        if (problems.any { it.blocksSave }) return open(Panel.Problems)
        panelView.addView(panelTitle(getString(R.string.editor_confirm_title)) { open(Panel.Idle) })
        panelView.addView(
            text(Ui.BODY, Ui.INK_SECONDARY).apply {
                text = getString(R.string.editor_confirm_body, graph.roomDestinations().size)
            },
            spaced(top = 0),
        )
        val toCheck = reviewedRooms().count { it.check != RoomCheck.CLEAR }
        val rows = buildList {
            if (toCheck > 0) {
                add(
                    listRow(
                        resources.getQuantityString(R.plurals.poster_need_look, toCheck, toCheck),
                    ) { open(Panel.Rooms) },
                )
            }
            for (warning in problems) {
                add(
                    listRow(warning.message) {
                        panel = Panel.Idle
                        warning.at?.let { map.focus(listOf(it)) }
                        render(warning.message)
                    },
                )
            }
        }
        if (rows.isNotEmpty()) {
            panelView.addView(
                spacedRows(rows, maxPx = resources.displayMetrics.heightPixels * 25 / 100),
                spaced(top = 8),
            )
        }
        val keep = styledButton(
            ButtonStyle.SECONDARY,
            getString(R.string.editor_keep_checking)
        ).apply {
            setOnClickListener { open(Panel.Idle) }
        }
        val save = styledButton(ButtonStyle.PRIMARY, getString(R.string.editor_save)).apply {
            setOnClickListener { save(this) }
        }
        panelView.addView(pair(keep, save), spaced(top = 8))
    }

    /** The session makes the last check and marks the graph verified. Then it joins Your maps. */
    private fun save(button: View) {
        button.isEnabled = false
        val checked = graph
        val stamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date())
        val name = getString(R.string.map_default_name, stamp)
        val maps = MapAssets.savedMaps(this)
        worker.execute {
            val saved = runCatching {
                val session = NavigationSession(AStarRouter())
                session.onMapCaptured(checked)
                session.onGraphVerified(checked)
                drafts.saveTo(maps, checkNotNull(session.graph), name)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                saved.onSuccess { map ->
                    startActivities(
                        arrayOf(
                            Intent(this, HomeActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK,
                            ),
                            IndoorNavActivity.open(this, map.id)
                                .putExtra(IndoorNavActivity.EXTRA_JUST_SAVED, true),
                        ),
                    )
                    finish()
                }.onFailure {
                    Log.w(TAG, "Map not saved", it)
                    render(getString(R.string.editor_save_failed), warn = true)
                }
            }
        }
    }

    private fun showRooms() {
        checkingList = true
        val photo = image ?: return
        panelView.addView(panelTitle(getString(R.string.editor_rooms_title)) { open(Panel.Idle) })
        roomPanel.show(shownRooms, photo)
        (roomPanel.parent as? LinearLayout)?.removeView(roomPanel)
        panelView.addView(
            roomPanel,
            LinearLayout.LayoutParams(-1, resources.displayMetrics.heightPixels * 42 / 100),
        )
    }

    private fun showRoom(index: Int) {
        val draft = history.current
        val room = draft.rooms.getOrNull(index) ?: return open(Panel.Rooms)
        val order = draft.checkOrder(index)
        val left = draft.roomsToCheck().size
        panelView.addView(readingHeader(room, roomTitle(room, left)) { open(roomsOrIdle()) })
        val next = draft.nextToCheck(order)
        panelView.addView(
            toolRow(
                buildList {
                    add(
                        Tool(R.drawable.ic_check, getString(R.string.editor_room_right)) {
                            edit(fixedMessage(room), afterFix(index, order)) {
                                it.acceptRoom(index)
                            }
                        },
                    )
                    add(
                        Tool(R.drawable.ic_edit, getString(R.string.editor_number)) {
                            open(Panel.Type(Typing.RoomNumber(index), panel))
                        },
                    )
                    add(
                        Tool(R.drawable.ic_door, getString(R.string.editor_room_door)) {
                            val box = room.toPreview().bounds
                            startPlacing(
                                Goal.RoomDoor(index),
                                room.entranceNodeId?.let { graph.nodes[it]?.position }
                                    ?: PixelPoint(
                                        box.exactCenterX().toDouble(),
                                        box.exactCenterY().toDouble(),
                                    ),
                            )
                        },
                    )
                    add(
                        Tool(R.drawable.ic_delete, getString(R.string.editor_room_remove)) {
                            edit(R.string.editor_deleted, afterFix(null, order)) {
                                it.removeRoom(index)
                            }
                        },
                    )
                    if (next != null && next != index) {
                        add(
                            Tool(
                                R.drawable.ic_chevron_right,
                                getString(R.string.editor_room_skip)
                            ) {
                                followRoom = true
                                open(Panel.Room(next))
                            },
                        )
                    }
                },
            ),
        )
    }

    private fun fixedMessage(room: RoomDraft) =
        if (room.entranceNodeId in graph.nodes) {
            R.string.editor_number_saved
        } else {
            R.string.editor_room_needs_door
        }

    private fun roomTitle(room: RoomDraft, left: Int): String {
        val read = getString(R.string.editor_room_title, room.label)
        if (left == 0) return read
        return read + "\n" + resources.getQuantityString(R.plurals.editor_rooms_left, left, left)
    }

    private fun pointName(id: String): String {
        val node = graph.nodes.getValue(id)
        val kind = getString(
            when (node.type) {
                NodeType.START -> R.string.editor_type_start
                NodeType.DOOR -> R.string.editor_type_door
                NodeType.JUNCTION -> R.string.editor_type_junction
                NodeType.DEAD_END -> R.string.editor_type_end
                NodeType.ELEVATOR -> R.string.editor_type_elevator
                NodeType.STAIRS -> R.string.editor_type_stairs
                NodeType.EXIT -> R.string.editor_type_exit
                NodeType.AED -> R.string.editor_type_aed
            },
        )
        val labels = graph.landmarks.filter { it.nodeId == id }.joinToString { it.label }
        return if (labels.isEmpty()) kind else "$kind $labels"
    }

    private fun reviewedRooms(): List<ReviewRoom> {
        val draft = history.current
        return draft.rooms.indices
            .map { ReviewRoom(draft.rooms[it].toPreview(), draft.checkOf(it)) }
            .sortedWith(compareBy({ it.check }, { it.preview.label }))
    }

    /**
     * Where to go after fixing a room: stay if it still needs something, like a door, otherwise
     * on to the next flagged room, so nobody has to go back to the list for each one.
     */
    private fun afterFix(fixed: Int?, order: String?): (MapDraft) -> Panel = { draft ->
        followRoom = true
        when {
            fixed != null && fixed in draft.roomsToCheck() -> Panel.Room(fixed)
            !checkingList -> Panel.Idle
            else -> draft.nextToCheck(order)?.let { Panel.Room(it) } ?: Panel.Rooms
        }
    }

    private fun roomsOrIdle(): Panel = if (checkingList) Panel.Rooms else Panel.Idle

    /** The photo of a printed number beside what the model read from it. */
    private fun readingHeader(room: RoomDraft, title: String, onClose: (() -> Unit)?): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    image?.let { setImageBitmap(labelCrop(it, room.toPreview().bounds)) }
                },
                LinearLayout.LayoutParams(dp(104), dp(56)).apply { marginEnd = dp(8) },
            )
            addView(panelTitle(title, onClose), LinearLayout.LayoutParams(0, -2, 1f))
        }

    /** [shownRooms] is sorted for display, so its position isn't the room's place in the draft. */
    private fun indexOfRoom(shown: Int): Int? {
        val preview = shownRooms.getOrNull(shown)?.preview ?: return null
        return history.current.rooms.indexOfFirst { it.toPreview() == preview }.takeIf { it >= 0 }
    }

    private fun RoomDraft.toPreview() =
        RoomPreview(label, Rect(left, top, right, bottom), entranceNodeId, confidence, possible)

    private fun pair(start: View, end: View): View = LinearLayout(this).apply {
        addView(start, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        addView(end, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private companion object {
        const val TAG = "GraphEditor"
    }
}
