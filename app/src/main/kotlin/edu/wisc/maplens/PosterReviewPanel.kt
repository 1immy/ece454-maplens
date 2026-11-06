package edu.wisc.maplens

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

private val SURFACE = Color.rgb(240, 242, 245)
private val SELECTED_SURFACE = Color.rgb(228, 238, 249)
private val ON_ACCENT = Color.rgb(250, 251, 252)
private const val CROP_MARGIN_PX = 24

/**
 * Every number the parser read, beside the photo it was read from, so nobody has to find
 * labels on a small map. Rooms that need a look get their own list.
 */
internal class PosterReviewPanel(context: Context) : LinearLayout(context) {
    /** Called when the list changes the picked room, so the map can mark it or clear it. */
    var onPick: ((ReviewRoom?) -> Unit)? = null

    private val attentionTab = tab()
    private val allTab = tab()
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { addView(list) }
    private val rows = mutableMapOf<ReviewRoom, View>()
    private var rooms: List<ReviewRoom> = emptyList()
    private var image: Bitmap? = null
    private var selected: ReviewRoom? = null
    private var showingAll = false

    init {
        orientation = VERTICAL
        val tabs = LinearLayout(context).apply {
            setPadding(0, dp(10), 0, dp(6))
            addView(attentionTab, LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            addView(allTab, LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        }
        attentionTab.setOnClickListener { switchTab(all = false) }
        allTab.setOnClickListener { switchTab(all = true) }
        addView(tabs, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    fun show(reviewed: List<ReviewRoom>, photo: Bitmap) {
        rooms = reviewed
        image = photo
        selected = null
        attentionTab.text = context.getString(
            R.string.poster_tab_attention,
            reviewed.count { it.check != RoomCheck.CLEAR },
        )
        allTab.text = context.getString(R.string.poster_tab_all, reviewed.size)
        showList(all = reviewed.none { it.check != RoomCheck.CLEAR })
    }

    /** Marks a room tapped on the map and shows its row, or clears the mark. */
    fun select(room: ReviewRoom?) {
        val previous = selected
        selected = room
        previous?.let { paintRow(it) }
        if (room == null) return
        if (room !in rows) showList(all = true)
        paintRow(room)
        rows[room]?.let { row -> row.post { scroll.smoothScrollTo(0, row.top) } }
    }

    private fun switchTab(all: Boolean) {
        if (all == showingAll) return
        selected = null
        onPick?.invoke(null)
        showList(all)
    }

    private fun showList(all: Boolean) {
        showingAll = all
        paintTab(attentionTab, !all)
        paintTab(allTab, all)
        list.removeAllViews()
        rows.clear()
        val shown = if (all) {
            rooms.sortedBy { it.preview.label }
        } else {
            rooms.filter { it.check != RoomCheck.CLEAR }
        }
        if (shown.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    setText(R.string.poster_nothing_to_check)
                    textSize = 15f
                    setTextColor(POSTER_MUTED)
                    setPadding(0, dp(16), 0, dp(16))
                },
            )
        }
        for (room in shown) {
            val row = row(room)
            rows[room] = row
            list.addView(row, LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        scroll.scrollTo(0, 0)
    }

    private fun row(room: ReviewRoom): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(dp(8), dp(8), dp(12), dp(8))
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(
            R.string.poster_room_description,
            room.preview.label,
            context.getString(reason(room.check)),
        )
        addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageBitmap(labelCrop(room.preview.bounds))
            },
            LayoutParams(dp(104), dp(56)).apply { marginEnd = dp(12) },
        )
        addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(
                    TextView(context).apply {
                        text = room.preview.label + if (room.isDoubtful) "?" else ""
                        textSize = 20f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(POSTER_TEXT)
                    },
                )
                addView(
                    TextView(context).apply {
                        setText(reason(room.check))
                        textSize = 13f
                        setTextColor(
                            if (room.check == RoomCheck.CLEAR) POSTER_MUTED else REVIEW_CHECK,
                        )
                    },
                )
            },
            LayoutParams(0, -2, 1f),
        )
        setOnClickListener {
            val picked = room.takeIf { it != selected }
            select(picked)
            onPick?.invoke(picked)
        }
    }.also { paintRow(it, room) }

    private fun paintRow(room: ReviewRoom) {
        rows[room]?.let { paintRow(it, room) }
    }

    private fun paintRow(row: View, room: ReviewRoom) {
        val chosen = room == selected
        row.isSelected = chosen
        row.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, 32, 33, 36)),
            rounded(if (chosen) SELECTED_SURFACE else SURFACE).apply {
                if (chosen) setStroke(dp(2), POSTER_ACCENT)
            },
            null,
        )
    }

    private fun tab() = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
    }

    private fun paintTab(view: TextView, active: Boolean) {
        view.isSelected = active
        view.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.setTextColor(if (active) ON_ACCENT else POSTER_TEXT)
        view.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, 32, 33, 36)),
            rounded(if (active) POSTER_ACCENT else SURFACE),
            null,
        )
    }

    private fun reason(check: RoomCheck) = when (check) {
        RoomCheck.POSSIBLE -> R.string.poster_check_possible
        RoomCheck.NOT_SURE -> R.string.poster_check_not_sure
        RoomCheck.ENTRANCE -> R.string.poster_check_entrance
        RoomCheck.CLEAR -> R.string.poster_check_clear
    }

    private fun labelCrop(bounds: Rect): Bitmap? {
        val photo = image ?: return null
        val left = (bounds.left - CROP_MARGIN_PX).coerceAtLeast(0)
        val top = (bounds.top - CROP_MARGIN_PX).coerceAtLeast(0)
        val right = (bounds.right + CROP_MARGIN_PX).coerceAtMost(photo.width)
        val bottom = (bounds.bottom + CROP_MARGIN_PX).coerceAtMost(photo.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(photo, left, top, right - left, bottom - top)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
