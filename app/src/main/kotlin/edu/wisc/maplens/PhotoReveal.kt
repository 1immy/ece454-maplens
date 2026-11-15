package edu.wisc.maplens

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator

/**
 * The poster photo coming clear as it is read, in the spirit of an image resolving while it
 * is generated: the photo starts under a soft white veil, and the veil lifts from the top as
 * the parse really advances ([reveal]), with a feathered edge rather than a hard line. It
 * never loops or moves on its own: no progress, no motion. With animations turned off on the
 * phone it steps instead of gliding.
 */
internal class PhotoReveal(
    /** Where the photo is drawn inside the view (it is letterboxed); null for all of it. */
    private val area: () -> Rect? = { null },
) : Drawable() {
    private val veil = Paint()
    private var shown = 0f
    private val ease = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = REVEAL_MS
        interpolator = DecelerateInterpolator(1.6f)
    }

    /** Lifts the veil to [fraction] (0 to 1) of the photo's height. */
    fun reveal(fraction: Float) {
        val from = shown
        val to = fraction.coerceIn(0f, 1f)
        if (to <= from) return
        ease.cancel()
        ease.removeAllUpdateListeners()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shown = to
            invalidateSelf()
            return
        }
        ease.addUpdateListener {
            shown = from + (to - from) * it.animatedFraction
            invalidateSelf()
        }
        ease.start()
    }

    fun stop() = ease.cancel()

    override fun draw(canvas: Canvas) {
        val b = area() ?: bounds
        if (b.isEmpty || shown >= 1f) return
        val feather = b.height() * FEATHER_SHARE
        // The clear part ends at `edge`; the veil fades in over `feather` below it.
        val edge = b.top + (b.height() + feather) * shown - feather
        veil.shader = LinearGradient(
            0f,
            edge,
            0f,
            edge + feather,
            intArrayOf(Color.argb(0, 248, 249, 250), VEIL),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(
            b.left.toFloat(),
            maxOf(edge, b.top.toFloat()),
            b.right.toFloat(),
            b.bottom.toFloat(),
            veil,
        )
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** How long the veil takes to catch up with a step of progress. */
        const val REVEAL_MS = 650L

        /** The soft edge between clear and veiled, as a share of the photo's height. */
        const val FEATHER_SHARE = 0.18f

        /** Frosted, not blank: the map still shows through, like glass misting over it. */
        val VEIL = Color.argb(196, 248, 249, 250)
    }
}
