package edu.wisc.maplens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * First-run art on Home: an evacuation poster on the wall with a phone framing it, so the
 * one thing to do is clear before any words. Drawn in a 358 x 220 box, fitted to the view.
 */
internal class HomeArt(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 24 * resources.displayMetrics.density
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.color = Ui.SURFACE_RAISED
        canvas.drawRoundRect(box, radius, radius, fill)
        val scale = min(width / W, height / H)
        canvas.save()
        canvas.translate((width - W * scale) / 2, (height - H * scale) / 2)
        canvas.scale(scale, scale)
        drawPoster(canvas)
        drawPhone(canvas)
        canvas.restore()
    }

    private fun drawPoster(canvas: Canvas) {
        fill.color = PAPER
        canvas.drawRoundRect(104f, 26f, 254f, 202f, 4f, 4f, fill)
        fill.color = FRAME
        canvas.drawRoundRect(104f, 26f, 254f, 40f, 2f, 2f, fill)
        line.color = FRAME
        line.strokeWidth = 3f
        canvas.drawRect(112f, 48f, 246f, 194f, line)
        line.color = ROUTE
        line.strokeWidth = 3f
        canvas.drawLine(120f, 120f, 238f, 120f, line)
        canvas.drawLine(179f, 56f, 179f, 186f, line)
        canvas.drawLine(136f, 120f, 136f, 70f, line)
        canvas.drawLine(222f, 120f, 222f, 170f, line)
        star(canvas, 179f, 178f)
    }

    private fun drawPhone(canvas: Canvas) {
        fill.color = Color.rgb(15, 23, 42)
        canvas.drawRoundRect(214f, 94f, 310f, 206f, 18f, 18f, fill)
        fill.color = Ui.ACCENT
        fill.alpha = 230
        canvas.drawRoundRect(220f, 100f, 304f, 200f, 13f, 13f, fill)
        fill.alpha = 255
        line.color = Color.WHITE
        line.strokeWidth = 2.4f
        path.reset()
        path.moveTo(234f, 128f)
        path.lineTo(234f, 116f)
        path.lineTo(246f, 116f)
        path.moveTo(278f, 116f)
        path.lineTo(290f, 116f)
        path.lineTo(290f, 128f)
        path.moveTo(290f, 172f)
        path.lineTo(290f, 184f)
        path.lineTo(278f, 184f)
        path.moveTo(246f, 184f)
        path.lineTo(234f, 184f)
        path.lineTo(234f, 172f)
        canvas.drawPath(path, line)
        canvas.drawCircle(262f, 150f, 10f, line)
    }

    private fun star(canvas: Canvas, x: Float, y: Float) {
        path.reset()
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) 6f else 2.6f
            val a = Math.toRadians(-90.0 + k * 36.0)
            val px = x + r * cos(a).toFloat()
            val py = y + r * sin(a).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fill.color = Color.rgb(224, 168, 0)
        canvas.drawPath(path, fill)
    }

    private companion object {
        const val W = 358f
        const val H = 220f
        val PAPER = Color.rgb(244, 240, 231)
        val FRAME = Color.rgb(43, 91, 168)
        val ROUTE = Color.rgb(232, 119, 46)
    }
}
