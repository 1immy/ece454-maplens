package edu.wisc.maplens.nav

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "Turn to face your route", drawn the way you hold the phone: you face the top of the dial.
 * The green arrow points the way to go, a curved arrow sweeps from you to it in the direction
 * to turn, and both follow the compass as you turn. Once you face the route the ring turns
 * green. [offsetRad] is the turn still needed: positive to the right (clockwise).
 */
class TurnDial(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6 * density
    }
    private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
        color = TICK
    }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        strokeCap = Paint.Cap.ROUND
        color = ACCENT_BLUE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    /** Shown offset, eased toward the latest so the arrow turns smoothly. */
    private var shown = 0f
    private var target = 0f
    private val ease = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 160
        interpolator = DecelerateInterpolator()
    }

    /** Within this, the user faces the route: green ring, no sweep. */
    var doneRad: Double = Math.toRadians(25.0)

    var offsetRad: Double = 0.0
        set(value) {
            field = value
            val from = shown
            // Take the short way round between the old and new angle.
            var to = value.toFloat()
            while (to - from > PI) to -= (2 * PI).toFloat()
            while (to - from < -PI) to += (2 * PI).toFloat()
            target = to
            ease.cancel()
            ease.removeAllUpdateListeners()
            ease.addUpdateListener {
                shown = from + (target - from) * it.animatedFraction
                invalidate()
            }
            ease.start()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = (DIAL_DP * density).toInt()
        setMeasuredDimension(
            resolveSize(side, widthMeasureSpec),
            resolveSize(side, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 10 * density
        val facing = abs(shown) <= doneRad
        // Ring: grey while turning, green once facing the route.
        ring.color = if (facing) GOOD else RING
        fill.color = if (facing) GOOD_SOFT else Color.TRANSPARENT
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        // Quarter ticks, so left, right and behind read at a glance.
        for (q in 0 until 4) {
            val a = q * PI / 2 - PI / 2
            val x0 = cx + (r - 14 * density) * cos(a).toFloat()
            val y0 = cy + (r - 14 * density) * sin(a).toFloat()
            val x1 = cx + (r - 4 * density) * cos(a).toFloat()
            val y1 = cy + (r - 4 * density) * sin(a).toFloat()
            canvas.drawLine(x0, y0, x1, y1, ticks)
        }
        // The turn still to make: an arc from straight ahead (top) to the route's way.
        if (!facing) {
            val inner = r - 30 * density
            oval.set(cx - inner, cy - inner, cx + inner, cy + inner)
            val sweepDeg = Math.toDegrees(shown.toDouble()).toFloat()
            canvas.drawArc(oval, -90f, sweepDeg, false, sweep)
            // Arrowhead at the arc's end, pointing along the turn.
            val end = (-PI / 2 + shown).toFloat()
            val ex = cx + inner * cos(end)
            val ey = cy + inner * sin(end)
            val along = end + if (shown > 0) (PI / 2).toFloat() else (-PI / 2).toFloat()
            arrowHead(canvas, ex, ey, along, 12 * density, ACCENT_BLUE)
        }
        // The route's way: a green arrow on the rim.
        val way = (-PI / 2 + shown).toFloat()
        val wx = cx + (r - 2 * density) * cos(way)
        val wy = cy + (r - 2 * density) * sin(way)
        arrowHead(canvas, wx, wy, way, 18 * density, GOOD)
        // You: a blue arrow at the centre, always facing up (the way the phone points).
        arrowHead(canvas, cx, cy - 14 * density, (-PI / 2).toFloat(), 26 * density, ACCENT_BLUE)
    }

    /** A filled triangle with its tip at ([x], [y]) pointing along [angle] (radians). */
    private fun arrowHead(
        canvas: Canvas,
        x: Float,
        y: Float,
        angle: Float,
        size: Float,
        color: Int
    ) {
        val back = angle + PI.toFloat()
        val half = 0.5f
        path.reset()
        path.moveTo(x, y)
        path.lineTo(x + size * cos(back - half), y + size * sin(back - half))
        path.lineTo(x + size * cos(back + half), y + size * sin(back + half))
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    private companion object {
        const val DIAL_DP = 168
        val RING = Color.rgb(218, 220, 224)
        val TICK = Color.rgb(154, 160, 166)
        val GOOD = Color.rgb(30, 142, 62)
        val GOOD_SOFT = Color.argb(28, 30, 142, 62)
        val ACCENT_BLUE = Color.rgb(26, 115, 232)
    }
}
