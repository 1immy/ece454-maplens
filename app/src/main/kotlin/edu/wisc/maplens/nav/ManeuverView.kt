package edu.wisc.maplens.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import edu.wisc.maplens.guidance.Maneuver
import kotlin.math.min

/**
 * A turn-by-turn maneuver drawn as one bold arrow, the way navigation banners show it: the
 * line comes up from the bottom and bends the way to go. Drawn on a 24-unit grid and scaled
 * to the view, so it stays sharp at any size.
 */
internal class ManeuverView(context: Context) : View(context) {
    var maneuver: Maneuver = Maneuver.STRAIGHT
        set(value) {
            if (field == value) return
            field = value
            contentDescription = null
            invalidate()
        }

    var color: Int = Color.WHITE
        set(value) {
            field = value
            line.color = value
            head.color = value
            invalidate()
        }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.2f
        color = Color.WHITE
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val arrow = Path()
    private val oval = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        canvas.scale(s, s)
        path.reset()
        when (maneuver) {
            Maneuver.STRAIGHT -> {
                path.moveTo(12f, 21f)
                path.lineTo(12f, 7f)
                drawHead(canvas, 12f, 3f, 0f)
            }
            Maneuver.TURN_RIGHT, Maneuver.TURN_LEFT -> {
                val m = if (maneuver == Maneuver.TURN_LEFT) -1f else 1f
                path.moveTo(12f - 4 * m, 21f)
                path.lineTo(12f - 4 * m, 13f)
                path.quadTo(12f - 4 * m, 9f, 12f, 9f)
                path.lineTo(12f + 3 * m, 9f)
                drawHead(canvas, 12f + 7.5f * m, 9f, 90f * m)
            }
            Maneuver.SLIGHT_RIGHT, Maneuver.SLIGHT_LEFT -> {
                val m = if (maneuver == Maneuver.SLIGHT_LEFT) -1f else 1f
                path.moveTo(12f - 3 * m, 21f)
                path.lineTo(12f - 3 * m, 14f)
                path.quadTo(12f - 3 * m, 11.5f, 12f + 0.5f * m, 8.5f)
                drawHead(canvas, 12f + 3.6f * m, 5.2f, 45f * m)
            }
            Maneuver.U_TURN -> {
                path.moveTo(15.5f, 21f)
                path.lineTo(15.5f, 10f)
                oval.set(8.5f, 6.5f, 15.5f, 13.5f)
                path.arcTo(oval, 0f, -180f)
                path.lineTo(8.5f, 13f)
                drawHead(canvas, 8.5f, 17.5f, 180f)
            }
            Maneuver.ARRIVE -> {
                // A pin, filled: the end of the route.
                path.reset()
                head.style = Paint.Style.FILL
                arrow.reset()
                arrow.moveTo(12f, 22f)
                arrow.cubicTo(10f, 18f, 5f, 14f, 5f, 9.5f)
                oval.set(5f, 2.5f, 19f, 16.5f)
                arrow.arcTo(oval, 180f, 180f)
                arrow.cubicTo(19f, 14f, 14f, 18f, 12f, 22f)
                arrow.close()
                arrow.addCircle(12f, 9.5f, 2.6f, Path.Direction.CCW)
                arrow.fillType = Path.FillType.EVEN_ODD
                canvas.drawPath(arrow, head)
            }
        }
        canvas.drawPath(path, line)
        canvas.restore()
    }

    /** Filled arrowhead with its tip at ([x], [y]), pointing up rotated by [deg] clockwise. */
    private fun drawHead(canvas: Canvas, x: Float, y: Float, deg: Float) {
        arrow.reset()
        arrow.moveTo(0f, 0f)
        arrow.lineTo(4.6f, 5.2f)
        arrow.lineTo(-4.6f, 5.2f)
        arrow.close()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(deg)
        canvas.drawPath(arrow, head)
        canvas.restore()
    }
}

/** A two-tone compass needle (red = poster north) that turns with the map. */
internal class CompassView(context: Context) : View(context) {
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(234, 67, 53) }
    private val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(154, 160, 166) }
    private val needle = Path()

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 24f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(s, s)
        needle.reset()
        needle.moveTo(0f, -8f)
        needle.lineTo(3f, 0f)
        needle.lineTo(-3f, 0f)
        needle.close()
        canvas.drawPath(needle, red)
        needle.reset()
        needle.moveTo(0f, 8f)
        needle.lineTo(3f, 0f)
        needle.lineTo(-3f, 0f)
        needle.close()
        canvas.drawPath(needle, grey)
        canvas.restore()
    }
}
