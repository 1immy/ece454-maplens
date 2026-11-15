package edu.wisc.maplens

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min

/**
 * Drawn over the camera preview while framing a poster, after Google's camera and Lens hints:
 * the map's blue frame outlined as it is found, a target circle with a dot that sits on the
 * side the user should move to (it reaches the centre when the phone is parallel to the wall),
 * and one short instruction in a chip at the top. Green means ready; amber, worth fixing
 * first. Nothing here blocks the shutter.
 */
internal class AimOverlay(context: Context) : FrameLayout(context) {
    private val marks = Marks(context)
    private val chip = TextView(context).apply {
        textSize = Ui.CAPTION
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        background = context.rounded(CHIP, radiusDp = 20)
        setPadding(context.dp(14), context.dp(8), context.dp(16), context.dp(8))
        compoundDrawablePadding = context.dp(8)
        // TalkBack reads each new instruction without stealing focus.
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var shown: AimState? = null

    init {
        addView(marks, LayoutParams(-1, -1))
        addView(
            chip,
            LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = context.dp(16)
            },
        )
        show(AimState(AimHint.FIND, AimTone.NEUTRAL))
    }

    fun show(state: AimState) {
        val before = shown
        shown = state
        marks.target(state)
        if (before?.hint == state.hint && before.tone == state.tone) return
        val (text, iconId) = when (state.hint) {
            AimHint.FIND -> R.string.aim_find to R.drawable.ic_aim_frame
            AimHint.GLARE -> R.string.aim_glare to R.drawable.ic_aim_frame
            AimHint.STEP_BACK -> R.string.aim_step_back to R.drawable.ic_aim_out
            AimHint.STEP_LEFT -> R.string.aim_step_left to R.drawable.ic_aim_left
            AimHint.STEP_RIGHT -> R.string.aim_step_right to R.drawable.ic_aim_right
            AimHint.RAISE -> R.string.aim_raise to R.drawable.ic_aim_up
            AimHint.LOWER -> R.string.aim_lower to R.drawable.ic_aim_down
            AimHint.MOVE_CLOSER -> R.string.aim_closer to R.drawable.ic_aim_in
            AimHint.HOLD_STEADY -> R.string.aim_steady to R.drawable.ic_aim_frame
            AimHint.READY -> R.string.aim_ready to R.drawable.ic_check
        }
        chip.setText(text)
        chip.setCompoundDrawablesRelative(
            context.icon(iconId, toneColor(state.tone), sizeDp = 20),
            null,
            null,
            null,
        )
        chip.alpha = 0.4f
        chip.animate().alpha(1f).setDuration(FADE_MS).start()
        if (state.hint == AimHint.READY && before?.hint != AimHint.READY) {
            performHapticFeedback(READY_HAPTIC)
        }
    }

    /** Hides the outline (photo taken, or the camera stopped) without forgetting the chip. */
    fun clear() = show(AimState(AimHint.FIND, AimTone.NEUTRAL))

    private class Marks(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        private val tether = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1.5f * density
            color = Color.argb(150, 255, 255, 255)
        }
        private val path = Path()
        private var state = AimState(AimHint.FIND, AimTone.NEUTRAL)

        /** Shown dot position (degrees), eased towards the latest so it glides. */
        private var dotX = 0f
        private var dotY = 0f
        private var color = Color.WHITE
        private val glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLIDE_MS
            interpolator = DecelerateInterpolator()
        }

        fun target(next: AimState) {
            state = next
            val fromX = dotX
            val fromY = dotY
            val toX = next.moveX.toFloat()
            val toY = next.moveY.toFloat()
            val fromColor = color
            val toColor = toneColor(next.tone)
            glide.cancel()
            glide.removeAllUpdateListeners()
            glide.addUpdateListener {
                val t = it.animatedFraction
                dotX = fromX + (toX - fromX) * t
                dotY = fromY + (toY - fromY) * t
                color = blend(fromColor, toColor, t)
                invalidate()
            }
            glide.start()
        }

        override fun onDraw(canvas: Canvas) {
            val corners = state.corners
            if (corners != null && state.width > 0) drawFrame(canvas, corners)
            if (corners != null) drawLevel(canvas)
        }

        /** The camera image is fitted inside this view (PreviewView FIT_CENTER), centred. */
        private fun drawFrame(canvas: Canvas, corners: DoubleArray) {
            val scale = min(width.toFloat() / state.width, height.toFloat() / state.height)
            val left = (width - state.width * scale) / 2
            val top = (height - state.height * scale) / 2
            val xs = FloatArray(4) { left + corners[2 * it].toFloat() * scale }
            val ys = FloatArray(4) { top + corners[2 * it + 1].toFloat() * scale }
            path.reset()
            path.moveTo(xs[0], ys[0])
            for (i in 1..3) path.lineTo(xs[i], ys[i])
            path.close()
            fill.color = Color.argb(36, Color.red(color), Color.green(color), Color.blue(color))
            outline.color = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
            bracket.color = color
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
            // Corner brackets: a short stroke along each edge out of every corner.
            val reach = BRACKET_DP * density
            for (i in 0..3) {
                for (j in listOf((i + 1) % 4, (i + 3) % 4)) {
                    val dx = xs[j] - xs[i]
                    val dy = ys[j] - ys[i]
                    val length = hypot(dx, dy)
                    if (length < 1f) continue
                    val share = min(reach / length, 0.4f)
                    canvas.drawLine(xs[i], ys[i], xs[i] + dx * share, ys[i] + dy * share, bracket)
                }
            }
        }

        /** Target circle in the middle; the dot shows how far to move, and which way. */
        private fun drawLevel(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = RING_DP * density
            val perDegree = radius / AimParams().straightDegrees.toFloat()
            var x = dotX * perDegree
            var y = dotY * perDegree
            val far = hypot(x, y)
            val limit = REACH_DP * density
            if (far > limit) {
                x *= limit / far
                y *= limit / far
            }
            ring.color = if (state.tone == AimTone.GOOD) color else Color.argb(220, 255, 255, 255)
            ringFill.color = if (state.tone == AimTone.GOOD) {
                Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
            } else {
                Color.argb(40, 0, 0, 0)
            }
            canvas.drawCircle(cx, cy, radius, ringFill)
            canvas.drawCircle(cx, cy, radius, ring)
            if (hypot(x, y) > radius) canvas.drawLine(cx, cy, cx + x, cy + y, tether)
            dot.color = color
            val size = DOT_DP * density
            canvas.drawCircle(cx + x, cy + y, size, dot)
            canvas.drawCircle(cx + x, cy + y, size, dotEdge)
        }
    }

    private companion object {
        /** Google grey 900 at 85%, as camera chips over a live image. */
        val CHIP = Color.argb(217, 32, 33, 36)
        val GOOD = Color.rgb(52, 168, 83)
        val WARN = Color.rgb(251, 188, 4)
        const val FADE_MS = 160L
        const val GLIDE_MS = 180L
        const val RING_DP = 26f
        const val REACH_DP = 84f
        const val DOT_DP = 7f
        const val BRACKET_DP = 22f
        val READY_HAPTIC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }

        fun toneColor(tone: AimTone) = when (tone) {
            AimTone.GOOD -> GOOD
            AimTone.WARN -> WARN
            AimTone.NEUTRAL -> Color.WHITE
        }

        fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
        )
    }
}
