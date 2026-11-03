package edu.wisc.maplens.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Panorama-style aim guide: a fixed ring in the centre and a dot at the next photo's direction. */
class ScanGuideView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * density
        color = Color.argb(160, 255, 255, 255)
    }

    /** Offset of the target from the centre, in view pixels; null hides the dot. */
    private var target: Pair<Float, Float>? = null
    private var onTarget = false
    private var horizonY: Float? = null

    fun show(target: Pair<Float, Float>?, onTarget: Boolean, horizonY: Float?) {
        this.target = target
        this.onTarget = onTarget
        this.horizonY = horizonY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        horizonY?.let { canvas.drawLine(0f, cy + it, width.toFloat(), cy + it, horizon) }
        canvas.drawCircle(cx, cy, 34 * density, ring)
        val (dx, dy) = target ?: return
        dot.color = if (onTarget) Color.rgb(64, 200, 140) else Color.rgb(255, 196, 0)
        val r = 16 * density
        canvas.drawCircle(
            (cx + dx).coerceIn(r, width - r),
            (cy + dy).coerceIn(r, height - r),
            r,
            dot,
        )
    }
}
