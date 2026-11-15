package edu.wisc.maplens

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import edu.wisc.maplens.mapparse.ParseProgress
import edu.wisc.maplens.mapparse.ParseStage
import kotlin.math.roundToInt

internal val POSTER_ACCENT = Color.rgb(26, 86, 150)
internal val POSTER_TEXT = Color.rgb(32, 33, 36)
internal val POSTER_MUTED = Color.rgb(95, 99, 104)
private val TRACK = Color.rgb(221, 225, 230)

/** Where each stage starts on the bar, from warmed parse timings on the emulator. */
private val STAGE_START = mapOf(
    ParseStage.STRAIGHTEN to 0.0,
    ParseStage.CORRIDORS to 0.03,
    ParseStage.FIND_TEXT to 0.07,
    ParseStage.READ_ROOMS to 0.32,
    ParseStage.RECHECK_SMALL to 0.72,
    ParseStage.PLACE_ROOMS to 0.85,
)

/**
 * Shows the parser's real stage and counts; nothing here advances on a timer. Everyone sees
 * one line saying what is happening now, in a soft shimmer, and a slim rounded bar.
 * [developer] mode also keeps the list of stages, each ticked as it finishes.
 */
internal class PosterProgressView(
    context: Context,
    private val developer: Boolean = false,
) : LinearLayout(context) {
    private inner class Row(stage: ParseStage, val label: String) {
        val mark = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
        }
        val spinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(POSTER_ACCENT)
        }
        val title = TextView(context).apply {
            text = label
            textSize = 15f
        }
        val count = TextView(context).apply {
            textSize = 14f
            setTextColor(POSTER_MUTED)
        }
        val view = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            val slot = FrameLayout(context).apply {
                addView(mark, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                addView(spinner, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }
            addView(slot, LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
            addView(title, LayoutParams(0, -2, 1f))
            addView(count)
        }
        val order = stage.ordinal

        fun render(current: ParseProgress) {
            val done = order < current.stage.ordinal
            val active = order == current.stage.ordinal
            spinner.visibility = if (active) View.VISIBLE else View.INVISIBLE
            mark.text = if (done) "✓" else if (active) "" else "○"
            mark.setTextColor(if (done) POSTER_ACCENT else POSTER_MUTED)
            title.setTextColor(if (done || active) POSTER_TEXT else POSTER_MUTED)
            count.text = if (active && current.total > 0) {
                context.getString(R.string.poster_stage_count, current.done, current.total)
            } else {
                ""
            }
            val state = when {
                done -> R.string.poster_stage_done
                active -> R.string.poster_stage_active
                else -> R.string.poster_stage_waiting
            }
            view.contentDescription = "$label, ${context.getString(state)}"
        }
    }

    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = BAR_MAX
        // Rounded track and fill, like a modern linear indicator.
        progressDrawable = android.graphics.drawable.LayerDrawable(
            arrayOf(
                rounded(TRACK),
                android.graphics.drawable.ClipDrawable(
                    rounded(POSTER_ACCENT),
                    Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL,
                ),
            ),
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** What is happening now, e.g. "Reading room numbers · 12 of 40". */
    private val now = TextView(context).apply {
        textSize = 15f
        setTextColor(POSTER_TEXT)
        typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** How far the parse has really got, 0 to 1, each time it advances (for the photo). */
    var onFraction: ((Float) -> Unit)? = null
    private val rows = listOf(
        Row(ParseStage.STRAIGHTEN, context.getString(R.string.poster_stage_straighten)),
        Row(ParseStage.CORRIDORS, context.getString(R.string.poster_stage_corridors)),
        Row(ParseStage.FIND_TEXT, context.getString(R.string.poster_stage_find_text)),
        Row(ParseStage.READ_ROOMS, context.getString(R.string.poster_stage_read_rooms)),
        Row(ParseStage.RECHECK_SMALL, context.getString(R.string.poster_stage_recheck)),
        Row(ParseStage.PLACE_ROOMS, context.getString(R.string.poster_stage_place_rooms)),
    )

    init {
        orientation = VERTICAL
        setPadding(0, dp(16), 0, dp(8))
        addView(now, LayoutParams(-1, -2))
        addView(
            bar,
            LayoutParams(-1, dp(6)).apply {
                topMargin = dp(10)
                bottomMargin = dp(if (developer) 12 else 4)
            }
        )
        if (developer) rows.forEach { addView(it.view, LayoutParams(-1, -2)) }
    }

    fun show(progress: ParseProgress) {
        if (developer) rows.forEach { it.render(progress) }
        val phrase = context.getString(
            when (progress.stage) {
                ParseStage.STRAIGHTEN -> R.string.poster_now_straighten
                ParseStage.CORRIDORS -> R.string.poster_now_corridors
                ParseStage.FIND_TEXT -> R.string.poster_now_find_text
                ParseStage.READ_ROOMS -> R.string.poster_now_read_rooms
                ParseStage.RECHECK_SMALL -> R.string.poster_now_recheck
                ParseStage.PLACE_ROOMS -> R.string.poster_now_place_rooms
            },
        )
        now.text = if (progress.total > 0) {
            context.getString(R.string.poster_now_count, phrase, progress.done, progress.total)
        } else {
            phrase
        }
        val start = STAGE_START.getValue(progress.stage)
        val next = STAGE_START.entries.firstOrNull { it.key > progress.stage }?.value ?: 1.0
        val within = if (progress.total > 0) progress.done.toDouble() / progress.total else 0.0
        val fraction = start + (next - start) * within
        bar.setProgress((fraction * BAR_MAX).roundToInt(), true)
        onFraction?.invoke(fraction.toFloat())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(3).toFloat()
    }

    private companion object {
        const val BAR_MAX = 1000
    }
}
