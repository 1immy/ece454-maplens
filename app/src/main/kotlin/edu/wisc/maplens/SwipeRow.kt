package edu.wisc.maplens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** What a swipe on a [SwipeRow] does, drawn on the strip it uncovers. */
internal class SwipeAction(
    val iconId: Int,
    val label: String,
    val color: Int,
    /** True when the row leaves the list (delete); false when it springs back (pin). */
    val removes: Boolean,
    val run: () -> Unit,
)

/**
 * A list row that swipes sideways, as in Gmail: right uncovers [startAction], left uncovers
 * [endAction]. Released past [TRIGGER_SHARE] of its width, the action runs; short of it, the
 * row springs back. Taps and long presses reach [card] as usual; [swipeEnabled] turns
 * swiping off (in selection mode).
 */
@SuppressLint("ViewConstructor")
internal class SwipeRow(
    context: Context,
    private val card: View,
    private val startAction: SwipeAction,
    private val endAction: SwipeAction,
) : FrameLayout(context) {
    var swipeEnabled = true

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** Past the trigger point: letting go now runs the action. */
    private var armed = false
    private var velocity: VelocityTracker? = null
    private val flingSpeed =
        ViewConfiguration.get(context).scaledMinimumFlingVelocity * FLING_FACTOR

    private val startSide = side(startAction, Gravity.START)
    private val endSide = side(endAction, Gravity.END)
    private val back = FrameLayout(context).apply {
        visibility = View.INVISIBLE
        addView(startSide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(endSide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    init {
        addView(back, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun side(action: SwipeAction, gravity: Int): View = LinearLayout(context).apply {
        this.gravity = Gravity.CENTER_VERTICAL or gravity
        background = context.rounded(action.color)
        setPadding(context.dp(24), 0, context.dp(24), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val iconView = ImageView(context).apply {
            setImageDrawable(context.icon(action.iconId, Ui.ON_ACCENT))
            tag = ICON_TAG
        }
        val label = TextView(context).apply {
            text = action.label
            textSize = Ui.CAPTION
            setTextColor(Ui.ON_ACCENT)
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }
        val gap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        if (gravity == Gravity.START) {
            addView(iconView)
            addView(label, gap.apply { marginStart = context.dp(8) })
        } else {
            addView(label, gap.apply { marginEnd = context.dp(8) })
            addView(iconView)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                armed = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                velocity?.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > slop && abs(dx) > 1.5f * abs(dy)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility") // The card keeps its own click and actions.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        val dx = event.x - downX
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> drag(dx)
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                release(dx, velocity?.xVelocity ?: 0f)
            }
            MotionEvent.ACTION_CANCEL -> settle()
        }
        return true
    }

    private fun drag(dx: Float) {
        card.translationX = dx
        back.visibility = if (dx == 0f) View.INVISIBLE else View.VISIBLE
        startSide.visibility = if (dx > 0) View.VISIBLE else View.INVISIBLE
        endSide.visibility = if (dx < 0) View.VISIBLE else View.INVISIBLE
        // Crossing the trigger point is felt and seen, both ways, as in Gmail: a tick, and
        // the strip brightens and its icon pops; back under it, a lighter tick undoes that.
        val nowArmed = abs(dx) >= TRIGGER_SHARE * width
        if (nowArmed != armed) {
            armed = nowArmed
            performHapticFeedback(if (armed) ARM_HAPTIC else DISARM_HAPTIC)
            val scale = if (armed) ARMED_SCALE else 1f
            for (side in listOf(startSide, endSide)) {
                side.findViewWithTag<View>(ICON_TAG)?.animate()?.scaleX(scale)?.scaleY(scale)
                    ?.setDuration(ICON_MS)?.start()
            }
        }
        back.alpha = if (armed) 1f else 0.75f
    }

    private fun release(dx: Float, speed: Float) {
        dragging = false
        val action = if (dx > 0) startAction else endAction
        // Far enough, or a quick flick the same way, runs the action.
        val flung = abs(speed) >= flingSpeed && speed * dx > 0 && abs(dx) >= FLING_SHARE * width
        if (abs(dx) < TRIGGER_SHARE * width && !flung) return settle()
        // A flick never crossed the trigger point, so it gets its tick now.
        if (!armed) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (action.removes) {
            card.animate().translationX(if (dx > 0) width.toFloat() else -width.toFloat())
                .setDuration(ANIMATION_MS).withEndAction { action.run() }.start()
        } else {
            card.animate().translationX(0f).setDuration(ANIMATION_MS)
                .withEndAction {
                    back.visibility = View.INVISIBLE
                    action.run()
                }.start()
        }
    }

    private fun settle() {
        dragging = false
        armed = false
        for (side in listOf(startSide, endSide)) {
            side.findViewWithTag<View>(ICON_TAG)?.apply {
                scaleX = 1f
                scaleY = 1f
            }
        }
        card.animate().translationX(0f).setDuration(ANIMATION_MS)
            .withEndAction { back.visibility = View.INVISIBLE }.start()
    }

    private companion object {
        const val TRIGGER_SHARE = 0.35f
        const val FLING_SHARE = 0.15f
        const val FLING_FACTOR = 8
        const val ANIMATION_MS = 180L
        const val ICON_MS = 120L
        const val ARMED_SCALE = 1.3f
        const val ICON_TAG = "swipe-icon"

        // Android 14 has haptics made for crossing a gesture's threshold; before it, a tick.
        val ARM_HAPTIC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        val DISARM_HAPTIC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
    }
}
