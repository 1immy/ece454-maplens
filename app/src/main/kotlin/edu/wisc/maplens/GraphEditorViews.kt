package edu.wisc.maplens

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One action in the row under the map: an icon over a label, at least 64 dp tall. */
internal class Tool(val iconId: Int, val label: String, val action: () -> Unit)

internal fun Context.toolRow(tools: List<Tool>): View = LinearLayout(this).apply {
    for (tool in tools) {
        addView(toolView(tool), LinearLayout.LayoutParams(0, -1, 1f))
    }
}

private fun Context.toolView(tool: Tool): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    minimumHeight = dp(64)
    setPadding(dp(2), dp(8), dp(2), dp(8))
    background = pressable(rounded(Color.TRANSPARENT, radiusDp = 12))
    isClickable = true
    isFocusable = true
    contentDescription = tool.label
    setOnClickListener { tool.action() }
    addView(
        ImageView(context).apply {
            setImageDrawable(icon(tool.iconId, Ui.INK))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
        LinearLayout.LayoutParams(dp(24), dp(24)),
    )
    addView(
        text(Ui.CAPTION, Ui.INK).apply {
            text = tool.label
            gravity = Gravity.CENTER
            maxLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) },
    )
}

/** A square 48 dp icon button with a round ripple. */
internal fun Context.iconButton(iconId: Int, label: String, action: () -> Unit) =
    ImageButton(this).apply {
        setImageDrawable(icon(iconId, Ui.INK))
        background = pressable(rounded(Color.TRANSPARENT, radiusDp = 24))
        contentDescription = label
        setOnClickListener { action() }
    }

/** The panel's title, and a way out of it when there is one. */
internal fun Context.panelTitle(title: String, onClose: (() -> Unit)? = null): View =
    LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Ui.TOUCH_DP)
        addView(
            text(Ui.BODY, bold = true).apply {
                text = title
                markHeading()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (onClose != null) {
            val label = getString(R.string.editor_close)
            addView(
                iconButton(R.drawable.ic_close, label, onClose),
                LinearLayout.LayoutParams(dp(Ui.TOUCH_DP), dp(Ui.TOUCH_DP)),
            )
        }
    }

/** A full-width row of at least 52 dp, with an optional control at its end. */
internal fun Context.listRow(
    title: String,
    trailing: View? = null,
    action: (() -> Unit)? = null,
): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(Ui.TOUCH_DP + 4)
    setPadding(dp(12), dp(6), dp(if (trailing == null) 12 else 6), dp(6))
    background = pressable(rounded(Ui.BACKGROUND, radiusDp = 12))
    if (action != null) {
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }
    addView(
        text(Ui.BODY).apply { text = title },
        LinearLayout.LayoutParams(0, -2, 1f),
    )
    trailing?.let { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }) }
}

/** Grows with its rows up to [maxPx], then scrolls, so the map keeps its share of the screen. */
internal class CappedScroll(context: Context, private val maxPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }
}

internal fun Context.spacedRows(rows: List<View>, maxPx: Int): View {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    for (row in rows) {
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }
    return CappedScroll(this, maxPx).apply { addView(list) }
}

/** The short line above the map. Its color says whether something went wrong. */
internal fun TextView.setHint(message: CharSequence, warn: Boolean) {
    text = message
    setTextColor(if (warn) REVIEW_CHECK else Ui.INK_SECONDARY)
}
