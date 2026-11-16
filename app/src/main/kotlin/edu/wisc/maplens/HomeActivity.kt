package edu.wisc.maplens

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.transition.TransitionValues
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The app's entry. Scanning a poster is the one primary action, in the thumb zone. Saved maps
 * come first once there are any (pinned ones on top), each with a menu to rename, pin or
 * delete it; the introduction shows only before the first map. An unfinished scan, the bundled
 * EH3 map and the debug tools sit under Developer, shown only in developer mode (the switch at
 * the top right, behind a password).
 */
class HomeActivity : ComponentActivity() {
    /** Maps picked in selection mode (Gmail-style); empty when not selecting. */
    private val selected = linkedSetOf<String>()

    /** Maps deleted but still restorable from the Undo bar; removed for good after a while. */
    private val pendingDelete = linkedSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val commitDeletes = Runnable {
        commitPendingDeletes()
        refresh()
    }
    private val thumbs = HashMap<String, Bitmap>()
    private val thumbLoader = Executors.newSingleThreadExecutor()
    private var scroll: ScrollView? = null
    private val leaveSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selected.clear()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, leaveSelection)
    }

    // Built on resume so a map saved in the capture screen shows when the user comes back.
    override fun onResume() {
        super.onResume()
        refresh(animate = false)
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen ends the chance to undo.
        commitPendingDeletes()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbLoader.shutdownNow()
    }

    /**
     * Rebuilds the screen, keeping the scroll position. [animate]: cards, headers and bars
     * glide from where they were (matched by transition name), so a pinned map moves up into
     * Pinned and the cards under a deleted one close the gap, instead of the list jumping.
     */
    private fun refresh(animate: Boolean = true) {
        val y = scroll?.scrollY ?: 0
        val root = findViewById<ViewGroup>(android.R.id.content)
        if (animate && root.childCount > 0) {
            TransitionManager.beginDelayedTransition(root, listTransition())
        }
        setContentView(buildLayout())
        // Before the first frame, not after it: a posted scroll draws one frame at the top.
        scroll?.let { view ->
            view.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        view.viewTreeObserver.removeOnPreDrawListener(this)
                        view.scrollTo(0, y)
                        return true
                    }
                },
            )
        }
        leaveSelection.isEnabled = selected.isNotEmpty()
    }

    private fun listTransition() = TransitionSet().apply {
        ordering = TransitionSet.ORDERING_TOGETHER
        addTransition(NamedFade(Fade.OUT))
        addTransition(NamedBounds())
        addTransition(NamedFade(Fade.IN))
        duration = LIST_MS
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    /** Moves only named views; the rest of a rebuilt screen is drawn where it was. */
    private class NamedBounds : ChangeBounds() {
        override fun captureStartValues(values: TransitionValues) {
            if (values.view.transitionName != null) super.captureStartValues(values)
        }

        override fun captureEndValues(values: TransitionValues) {
            if (values.view.transitionName != null) super.captureEndValues(values)
        }
    }

    /** Fades named views in or out, so a card's own text and image don't flicker. */
    private class NamedFade(mode: Int) : Fade(mode) {
        override fun captureStartValues(values: TransitionValues) {
            if (values.view.transitionName != null) super.captureStartValues(values)
        }

        override fun captureEndValues(values: TransitionValues) {
            if (values.view.transitionName != null) super.captureEndValues(values)
        }
    }

    private fun buildLayout(): View {
        val saved = MapAssets.savedMaps(this).list().filter { it.id !in pendingDelete }
        selected.retainAll(saved.map { it.id }.toSet())
        val selecting = selected.isNotEmpty()
        val developer = AppMode.isDeveloper(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(if (selecting) 8 else 16), dp(16), dp(24))
            if (!selecting) addView(brand())
            if (saved.isEmpty()) {
                // First run: a picture of the one thing to do, then the words.
                addView(
                    HomeArt(context),
                    LinearLayout.LayoutParams(MATCH, dp(220)).apply { topMargin = dp(20) },
                )
                addView(
                    text(Ui.TITLE, bold = true).apply {
                        setText(R.string.home_title)
                        markHeading()
                    },
                    spaced(top = 24),
                )
                addView(
                    text(Ui.BODY, Ui.INK_SECONDARY).apply {
                        setText(R.string.home_intro)
                        transitionName = "intro"
                    },
                    spaced(top = 8),
                )
                addView(steps(), spaced(top = 24))
            } else {
                // Returning: the maps are what people came for.
                val (pinned, others) = saved.partition { it.pinned }
                if (pinned.isNotEmpty()) {
                    addView(
                        sectionHeader(R.string.home_pinned, pinned.size, select = !selecting),
                        spaced(top = if (selecting) 8 else 16),
                    )
                    pinned.forEachIndexed { i, map ->
                        addView(mapRow(map, selecting), spaced(top = if (i == 0) 12 else 8))
                    }
                }
                if (others.isNotEmpty()) {
                    addView(
                        sectionHeader(
                            if (pinned.isEmpty()) R.string.home_maps else R.string.home_other_maps,
                            others.size,
                            // Selection is one tap away too, not only behind a long press.
                            select = !selecting && pinned.isEmpty(),
                        ),
                        spaced(top = if (selecting) 8 else if (pinned.isEmpty()) 16 else 24),
                    )
                    others.forEachIndexed { i, map ->
                        addView(mapRow(map, selecting), spaced(top = if (i == 0) 12 else 8))
                    }
                }
                if (!selecting) {
                    addView(
                        text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                            setText(R.string.home_swipe_hint)
                            transitionName = "hint"
                        },
                        LinearLayout.LayoutParams(MATCH, WRAP).apply {
                            topMargin = dp(10)
                            marginStart = dp(4)
                        },
                    )
                }
            }
            // The team's tools: only in developer mode.
            if (developer) {
                addView(
                    sectionTitle(R.string.home_developer).apply { transitionName = "dev" },
                    spaced(top = 32),
                )
                // A scan left mid-check: picked up from here, not mixed in with finished maps.
                if (DraftStore.of(this@HomeActivity).exists) {
                    addView(
                        devRow(
                            R.drawable.ic_edit,
                            R.string.home_draft,
                            R.string.home_draft_detail
                        ) {
                            startActivity(
                                Intent(this@HomeActivity, GraphEditorActivity::class.java)
                            )
                        },
                        spaced(top = 12),
                    )
                }
                addView(
                    devRow(
                        R.drawable.ic_navigation,
                        R.string.home_dev_nav,
                        R.string.home_dev_nav_detail
                    ) {
                        startActivity(Intent(this@HomeActivity, IndoorNavActivity::class.java))
                    },
                    spaced(top = 12),
                )
                addView(
                    devRow(
                        R.drawable.ic_robot,
                        R.string.home_dev_temi,
                        R.string.home_dev_temi_detail,
                    ) { startActivity(Intent(this@HomeActivity, TemiDevActivity::class.java)) },
                    spaced(top = 8),
                )
                debugIntent(FIXTURE_EDITOR)?.let { editor ->
                    addView(
                        devRow(
                            R.drawable.ic_edit,
                            R.string.home_dev_edit,
                            R.string.home_dev_edit_detail,
                        ) { startActivity(editor) },
                        spaced(top = 8),
                    )
                }
                debugIntent(RECORDER)?.let { recorder ->
                    addView(
                        devRow(
                            R.drawable.ic_walk,
                            R.string.home_dev_recorder,
                            R.string.home_dev_recorder_detail,
                        ) { startActivity(recorder) },
                        spaced(top = 8),
                    )
                }
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        this.scroll = scroll
        val scan = styledButton(
            ButtonStyle.PRIMARY,
            getString(R.string.home_scan),
            R.drawable.ic_camera,
        ).apply {
            minHeight = dp(56)
            minimumHeight = dp(56)
            contentDescription = getString(R.string.home_scan)
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, PosterCaptureActivity::class.java))
            }
        }
        val bottom = LinearLayout(this).apply {
            transitionName = "scan"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BACKGROUND)
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(scan, LinearLayout.LayoutParams(MATCH, WRAP))
            if (saved.isNotEmpty()) {
                addView(
                    text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                        setText(R.string.home_scan_hint)
                        gravity = Gravity.CENTER
                    },
                    spaced(top = 10),
                )
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BACKGROUND)
            if (selecting) addView(selectionBar(saved), LinearLayout.LayoutParams(MATCH, WRAP))
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            if (pendingDelete.isNotEmpty()) {
                addView(
                    undoBar(),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        setMargins(dp(16), dp(8), dp(16), dp(8))
                    },
                )
            }
            // Like Gmail's compose button, scanning steps aside while maps are selected.
            if (!selecting) addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
            padForSystemBars()
        }
    }

    private fun brand(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        transitionName = "brand"
        addView(
            // The launcher icon itself, so the brand mark matches the home-screen icon. The
            // adaptive icon draws through the device's own mask shape.
            // Also the hidden way into developer mode: five quick taps, as Android's build
            // number. Users see a plain logo; nothing hints at it until the third tap.
            ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { logoTapped() }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        addView(
            text(22f, bold = true).apply { setText(R.string.app_name) },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) },
        )
        // Only in developer mode, so it's never forgotten on, and one tap turns it off.
        if (AppMode.isDeveloper(context)) {
            addView(modeSwitch(), LinearLayout.LayoutParams(WRAP, dp(Ui.TOUCH_DP)))
        }
    }

    private var logoTaps = 0
    private var lastLogoTapMs = 0L
    private var tapHint: Toast? = null

    /** Counts quick taps on the logo; the fifth offers developer mode. */
    private fun logoTapped() {
        if (AppMode.isDeveloper(this)) return
        val now = android.os.SystemClock.elapsedRealtime()
        logoTaps = if (now - lastLogoTapMs <= LOGO_TAP_GAP_MS) logoTaps + 1 else 1
        lastLogoTapMs = now
        tapHint?.cancel()
        val left = LOGO_TAPS - logoTaps
        if (left <= 0) {
            logoTaps = 0
            confirmDeveloperMode {
                AppMode.setDeveloper(this, true)
                Toast.makeText(this, R.string.mode_developer_on, Toast.LENGTH_SHORT).show()
                refresh()
            }
        } else if (logoTaps >= 3) {
            tapHint = Toast.makeText(
                this,
                resources.getQuantityString(R.plurals.mode_taps_left, left, left),
                Toast.LENGTH_SHORT,
            ).also { it.show() }
        }
    }

    /**
     * Top right in developer mode: a filled accent pill so nobody forgets it is on. Tapping it
     * goes back to user mode at once. (User mode shows no pill; see [logoTapped].)
     */
    private fun modeSwitch(): View = TextView(this).apply {
        setText(R.string.mode_developer)
        textSize = Ui.CAPTION
        typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        setTextColor(Ui.ON_ACCENT)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(14), 0)
        setCompoundDrawablesRelative(icon(R.drawable.ic_code, Ui.ON_ACCENT, 18), null, null, null)
        compoundDrawablePadding = dp(6)
        background = pressable(rounded(Ui.ACCENT, radiusDp = 24))
        isClickable = true
        contentDescription = getString(R.string.mode_switch_to_user)
        tooltipText = contentDescription
        setOnClickListener {
            AppMode.setDeveloper(context, false)
            Toast.makeText(context, R.string.mode_user_on, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun steps(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.SURFACE, radiusDp = 20, stroke = CARD_EDGE)
        elevation = dpf(1f)
        setPadding(dp(18), dp(2), dp(18), dp(18))
        val items = listOf(
            R.string.home_step_photo to R.string.home_step_photo_detail,
            R.string.home_step_check to R.string.home_step_check_detail,
            R.string.home_step_walk to R.string.home_step_walk_detail,
        )
        items.forEachIndexed { i, (title, detail) ->
            addView(step(i + 1, title, detail), spaced(top = 16))
        }
    }

    private fun step(number: Int, title: Int, detail: Int): View = LinearLayout(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "${getString(R.string.home_step_number, number)}. " +
            "${getString(title)}. ${getString(detail)}"
        addView(
            text(Ui.CAPTION, Ui.ACCENT, bold = true).apply {
                text = NumberFormat.getIntegerInstance().format(number)
                gravity = Gravity.CENTER
                background = rounded(Ui.ACCENT_SOFT, radiusDp = 16)
                textSize = 15f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(32), dp(32)),
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(text(Ui.BODY, bold = true).apply { setText(title) })
                addView(
                    text(Ui.CAPTION, Ui.INK_SECONDARY).apply { setText(detail) },
                    spaced(top = 2),
                )
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) },
        )
    }

    private fun sectionTitle(title: Int): View =
        text(Ui.CAPTION, Ui.INK_SECONDARY, bold = true).apply {
            setText(title)
            letterSpacing = 0.08f
            isAllCaps = true
            markHeading()
        }

    /** A section title with its map count, and a Select action on the right. */
    private fun sectionHeader(title: Int, count: Int, select: Boolean): View =
        LinearLayout(this).apply {
            transitionName = "header:$title"
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.TOUCH_DP)
            addView(sectionTitle(title), LinearLayout.LayoutParams(WRAP, WRAP))
            addView(
                text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                    val maps = resources.getQuantityString(R.plurals.home_map_count, count, count)
                    text = "· $maps"
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) },
            )
            if (select) {
                addView(
                    text(Ui.CAPTION, Ui.ACCENT, bold = true).apply {
                        setText(R.string.home_select)
                        gravity = Gravity.CENTER
                        minHeight = dp(Ui.TOUCH_DP)
                        setPadding(dp(16), 0, dp(16), 0)
                        background = pressable(rounded(Color.TRANSPARENT, radiusDp = 24))
                        isClickable = true
                        setOnClickListener { startSelection(null) }
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = -dp(12) },
                )
            }
        }

    /**
     * The top bar while maps are selected, as in Gmail: close, how many, then Select all,
     * Pin and Delete for every selected map.
     */
    private fun selectionBar(saved: List<SavedMap>): View = LinearLayout(this).apply {
        transitionName = "selection"
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Ui.SURFACE)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        elevation = dpf(3f)
        fun action(iconId: Int, description: String, onTap: () -> Unit) =
            ImageButton(context).apply {
                setImageDrawable(icon(iconId, Ui.INK))
                background = pressable(rounded(Color.TRANSPARENT, radiusDp = 24))
                contentDescription = description
                tooltipText = description
                setOnClickListener { onTap() }
            }
        val picked = saved.filter { it.id in selected }
        val all = picked.size == saved.size
        val pin = picked.any { !it.pinned }
        addView(
            action(R.drawable.ic_close, getString(R.string.home_selection_close)) {
                selected.clear()
                refresh()
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        addView(
            text(Ui.HEADING, bold = true).apply {
                text = getString(R.string.home_selected_count, picked.size)
                markHeading()
                // Screen readers hear the count as it changes.
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) },
        )
        addView(
            action(
                R.drawable.ic_select_all,
                getString(if (all) R.string.home_deselect_all else R.string.home_select_all),
            ) {
                if (all) selected.clear() else selected.addAll(saved.map { it.id })
                refresh()
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        addView(
            action(
                R.drawable.ic_push_pin,
                getString(if (pin) R.string.home_selection_pin else R.string.home_selection_unpin),
            ) {
                val store = MapAssets.savedMaps(this@HomeActivity)
                picked.forEach { store.setPinned(it.id, pin) }
                selected.clear()
                refresh()
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        addView(
            action(R.drawable.ic_delete, getString(R.string.home_selection_delete)) {
                confirmDelete(picked)
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
    }

    /** A dark bar above the scan button: what was deleted, and Undo while it still can be. */
    private fun undoBar(): View = LinearLayout(this).apply {
        transitionName = "undo"
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Ui.INK, radiusDp = 12)
        setPadding(dp(16), dp(4), dp(4), dp(4))
        elevation = dpf(6f)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(
            text(Ui.CAPTION, Ui.ON_ACCENT).apply {
                text = resources.getQuantityString(
                    R.plurals.home_deleted,
                    pendingDelete.size,
                    pendingDelete.size,
                )
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        addView(
            text(Ui.CAPTION, Ui.ACCENT_SOFT, bold = true).apply {
                setText(R.string.home_undo)
                gravity = Gravity.CENTER
                minHeight = dp(Ui.TOUCH_DP)
                setPadding(dp(16), 0, dp(16), 0)
                background = pressable(
                    rounded(Color.TRANSPARENT, radiusDp = 8),
                    Color.argb(56, 255, 255, 255),
                )
                isClickable = true
                setOnClickListener {
                    handler.removeCallbacks(commitDeletes)
                    pendingDelete.clear()
                    refresh()
                }
            },
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
    }

    /**
     * A saved map as a card with its poster's thumbnail. Tap opens it; a long press or a tap
     * on the thumbnail selects it. Swipe right to pin or unpin, left to delete (with Undo).
     * The ⋮ button offers Select, Rename, Pin and Delete.
     */
    private fun mapRow(map: SavedMap, selecting: Boolean): View {
        val picked = map.id in selected
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(map.savedAtMillis))
        val detail = getString(R.string.home_map_row_detail, map.rooms, date)
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(96)
            setPadding(dp(12), dp(12), dp(if (selecting) 16 else 4), dp(12))
            background = pressable(
                rounded(
                    if (picked) Ui.ACCENT_SOFT else Ui.SURFACE,
                    stroke = if (picked) Ui.ACCENT else CARD_EDGE,
                ),
            )
            isClickable = true
            isFocusable = true
            isSelected = picked
            contentDescription = if (picked) {
                getString(R.string.home_map_selected, "${map.name}. $detail")
            } else {
                "${map.name}. $detail"
            }
            setOnClickListener {
                if (selecting) {
                    toggle(map.id)
                } else {
                    startActivity(IndoorNavActivity.open(context, map.id))
                }
            }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                toggle(map.id)
                true
            }
        }
        card.addView(leading(map, picked), LinearLayout.LayoutParams(dp(72), dp(72)))
        card.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(
                    text(Ui.BODY, bold = true).apply {
                        text = map.name
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
                addView(
                    text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                        text = detail
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    spaced(top = 2),
                )
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) },
        )
        if (map.pinned && !selecting) {
            card.addView(
                ImageView(this).apply {
                    setImageDrawable(icon(R.drawable.ic_push_pin, Ui.ACCENT, 18))
                    scaleType = ImageView.ScaleType.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(24), dp(48)),
            )
        }
        if (!selecting) {
            card.addView(
                ImageButton(this).apply {
                    setImageDrawable(icon(R.drawable.ic_more_vert, Ui.INK_SECONDARY))
                    background = pressable(rounded(Color.TRANSPARENT, radiusDp = 24))
                    contentDescription = getString(R.string.home_map_actions, map.name)
                    setOnClickListener { showMapMenu(it, map) }
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
        val pinAction = SwipeAction(
            R.drawable.ic_push_pin,
            getString(if (map.pinned) R.string.home_swipe_unpin else R.string.home_swipe_pin),
            Ui.ACCENT,
            removes = false,
        ) {
            MapAssets.savedMaps(this).setPinned(map.id, !map.pinned)
            refresh()
        }
        val deleteAction = SwipeAction(
            R.drawable.ic_delete,
            getString(R.string.home_map_delete),
            DELETE_RED,
            removes = true,
        ) { confirmDelete(listOf(map)) }
        return SwipeRow(this, card, pinAction, deleteAction).apply {
            swipeEnabled = !selecting
            // The same map's card before and after a rebuild: it glides, not jumps.
            transitionName = "map:${map.id}"
        }
    }

    /** The poster's thumbnail, or a check mark while the map is selected. Tapping selects. */
    private fun leading(map: SavedMap, picked: Boolean): View = FrameLayout(this).apply {
        isClickable = true
        setOnClickListener { toggle(map.id) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (picked) {
            background = rounded(Ui.ACCENT, radiusDp = 12)
            addView(
                ImageView(context).apply {
                    setImageDrawable(icon(R.drawable.ic_check, Ui.ON_ACCENT, 28))
                    scaleType = ImageView.ScaleType.CENTER
                },
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
        } else {
            background = rounded(Ui.SURFACE_RAISED, radiusDp = 12)
            clipToOutline = true
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageDrawable(icon(R.drawable.ic_map, Ui.INK_SECONDARY))
            }
            addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
            showThumbnail(map, image)
        }
    }

    /** Puts the map's poster on [view], decoded small off the UI thread the first time. */
    private fun showThumbnail(map: SavedMap, view: ImageView) {
        thumbs[map.id]?.let {
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(it)
            return
        }
        view.tag = map.id
        val target = dp(112)
        thumbLoader.execute {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(map.imageFile.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                map.imageFile.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@execute
            runOnUiThread {
                thumbs[map.id] = bitmap
                if (view.tag == map.id) {
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** Selects or deselects [id]; the first selected map starts selection mode. */
    private fun toggle(id: String) {
        if (!selected.remove(id)) selected += id
        refresh()
    }

    /** Selection mode from Select or the menu, with [id] (or the first map) picked. */
    private fun startSelection(id: String?) {
        val first = id ?: MapAssets.savedMaps(this).list()
            .firstOrNull { it.id !in pendingDelete }?.id
        first?.let { selected += it }
        refresh()
    }

    /**
     * Takes [ids] off the list at once and offers Undo for a few seconds, as Gmail does,
     * before the maps are deleted from the phone. A new delete first finishes the last one.
     */
    /**
     * "Delete …?" before anything goes, for the menu, the selection bar and a swipe alike.
     * Delete is in red; Cancel (or Back, or a tap outside) keeps the maps, and a swiped card
     * slides back. Undo stays available for a few seconds after, as a second chance.
     */
    private fun confirmDelete(maps: List<SavedMap>) {
        if (maps.isEmpty()) return
        val title = if (maps.size == 1) {
            getString(R.string.home_map_delete_title, maps.single().name)
        } else {
            resources.getQuantityString(R.plurals.home_delete_many_title, maps.size, maps.size)
        }
        var deleted = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(
                resources.getQuantityString(R.plurals.home_delete_body, maps.size, maps.size),
            )
            .setPositiveButton(R.string.home_map_delete) { _, _ ->
                deleted = true
                deleteWithUndo(maps.map { it.id })
            }
            .setNegativeButton(android.R.string.cancel, null)
            // A card swiped away comes back when the delete is called off.
            .setOnDismissListener { if (!deleted) refresh() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(DELETE_RED)
    }

    private fun deleteWithUndo(ids: List<String>) {
        if (ids.isEmpty()) return
        commitPendingDeletes()
        pendingDelete += ids
        selected.removeAll(ids.toSet())
        handler.postDelayed(commitDeletes, UNDO_MS)
        refresh()
    }

    private fun commitPendingDeletes() {
        handler.removeCallbacks(commitDeletes)
        if (pendingDelete.isEmpty()) return
        val store = MapAssets.savedMaps(this)
        pendingDelete.forEach { id ->
            store.delete(id)
            thumbs.remove(id)
        }
        pendingDelete.clear()
    }

    private fun showMapMenu(anchor: View, map: SavedMap) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_SELECT, 0, R.string.home_select)
        menu.menu.add(0, MENU_RENAME, 1, R.string.home_map_rename)
        menu.menu.add(
            0,
            MENU_PIN,
            2,
            if (map.pinned) R.string.home_map_unpin else R.string.home_map_pin,
        )
        menu.menu.add(0, MENU_EDIT, 3, R.string.home_map_edit)
        menu.menu.add(0, MENU_DELETE, 4, R.string.home_map_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SELECT -> startSelection(map.id)
                MENU_RENAME -> rename(map)
                MENU_PIN -> {
                    MapAssets.savedMaps(this).setPinned(map.id, !map.pinned)
                    refresh()
                }
                MENU_EDIT -> editMap(map)
                MENU_DELETE -> confirmDelete(listOf(map))
            }
            true
        }
        menu.show()
    }

    /** Opens "Check the map" on a copy of [map]; saving there replaces this map. */
    private fun editMap(map: SavedMap) {
        val store = MapAssets.savedMaps(this)
        thumbLoader.execute {
            val opened = runCatching {
                val draft = draftOf(store.loadGraph(map), store.loadLabels(map))
                DraftStore.of(this).start(draft, map.id) { map.imageFile.copyTo(it) }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                opened.onSuccess {
                    startActivity(Intent(this, GraphEditorActivity::class.java))
                }.onFailure {
                    Log.w(TAG, "Could not open ${map.id} for editing", it)
                    Toast.makeText(this, R.string.editor_open_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun rename(map: SavedMap) {
        val field = EditText(this).apply {
            setText(map.name)
            setSelectAllOnFocus(true)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.home_map_name_hint)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.home_map_rename)
            .setView(box)
            .setPositiveButton(R.string.home_map_rename_save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) {
                    MapAssets.savedMaps(this).rename(map.id, name)
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
        )
        dialog.show()
        field.requestFocus()
    }

    private fun devRow(iconId: Int, title: Int, detail: Int, onTap: () -> Unit): View =
        devRow(iconId, getString(title), getString(detail), onTap = onTap)

    /** A full-width tappable row: icon tile, title and detail, then [trailing] or a chevron. */
    private fun devRow(
        iconId: Int,
        title: String,
        detail: String,
        trailing: View? = null,
        onTap: () -> Unit,
    ): View =
        LinearLayout(this).apply {
            transitionName = "dev:$title"
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = pressable(rounded(Ui.SURFACE, stroke = Ui.BORDER))
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $detail"
            setOnClickListener { onTap() }
            addView(
                FrameLayout(context).apply {
                    background = rounded(Ui.BACKGROUND, radiusDp = 12)
                    addView(
                        ImageView(context).apply {
                            setImageDrawable(icon(iconId, Ui.INK_SECONDARY))
                            scaleType = ImageView.ScaleType.CENTER
                        },
                        FrameLayout.LayoutParams(MATCH, MATCH),
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(
                        text(Ui.BODY, bold = true).apply {
                            text = title
                            // Saved maps keep their whole name: two lines before it is cut.
                            maxLines = if (trailing != null) 2 else 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                    )
                    addView(
                        text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                            text = detail
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        spaced(top = 2),
                    )
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) },
            )
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(dp(48), dp(48)))
            } else {
                addView(
                    ImageView(context).apply {
                        setImageDrawable(icon(R.drawable.ic_chevron_right, Ui.INK_SECONDARY))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) },
                )
            }
        }

    /** A debug-only screen, when this build has it (they live in the debug source set). */
    private fun debugIntent(name: String): Intent? {
        val intent = Intent().setComponent(ComponentName(this, name))
        return intent.takeIf { packageManager.resolveActivity(it, 0) != null }
    }

    private fun spaced(top: Int, wrap: Boolean = false) =
        LinearLayout.LayoutParams(if (wrap) WRAP else MATCH, WRAP).apply { topMargin = dp(top) }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

        /** A card's edge: just enough to lift it off the background. */
        val CARD_EDGE = android.graphics.Color.rgb(233, 236, 241)
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val RECORDER = "edu.wisc.maplens.debug.RecorderActivity"
        const val FIXTURE_EDITOR = "edu.wisc.maplens.debug.EditFixtureActivity"
        const val MENU_RENAME = 1
        const val MENU_PIN = 2
        const val MENU_DELETE = 3
        const val MENU_EDIT = 5
        const val TAG = "Home"
        const val MENU_SELECT = 4
        const val UNDO_MS = 5000L

        /** List changes (pin, delete, select): long enough to follow, short enough to feel quick. */
        const val LIST_MS = 260L

        /** Taps on the logo that open the developer password, each within the gap of the last. */
        const val LOGO_TAPS = 5
        const val LOGO_TAP_GAP_MS = 600L
        val DELETE_RED = android.graphics.Color.rgb(220, 38, 38)
    }
}
