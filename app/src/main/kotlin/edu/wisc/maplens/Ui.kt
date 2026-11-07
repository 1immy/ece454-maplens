package edu.wisc.maplens

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The app's design tokens and a few view factories, so every screen shares one look without a
 * Material Components dependency. Colors mirror `res/values/themes.xml`.
 *
 * Type scale (sp): [TITLE] 28, [HEADING] 20, [BODY] 16, [CAPTION] 14. Spacing follows a 4/8 dp
 * grid, and every control is at least [TOUCH_DP] tall.
 */
internal object Ui {
    val BACKGROUND = Color.rgb(246, 247, 249)
    val SURFACE = Color.WHITE
    val INK = Color.rgb(15, 23, 42)

    /** 7.6:1 on white: secondary text stays readable outdoors and at large font scales. */
    val INK_SECONDARY = Color.rgb(71, 85, 105)
    val BORDER = Color.rgb(226, 232, 240)
    val ACCENT = POSTER_ACCENT
    val ACCENT_SOFT = Color.rgb(228, 238, 249)
    val ON_ACCENT = Color.rgb(250, 251, 252)
    val DISABLED = Color.rgb(203, 213, 225)

    const val TITLE = 28f
    const val HEADING = 20f
    const val BODY = 16f
    const val CAPTION = 14f

    const val TOUCH_DP = 48
    const val RADIUS_DP = 16
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

internal fun Context.rounded(color: Int, radiusDp: Int = Ui.RADIUS_DP, stroke: Int? = null) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        stroke?.let { setStroke(dp(1), it) }
    }

/** A pressed ripple over [shape], so every tap gets feedback without moving the layout. */
internal fun Context.pressable(shape: Drawable, rippleColor: Int = Color.argb(36, 15, 23, 42)) =
    RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)

internal fun Context.text(
    sizeSp: Float,
    color: Int = Ui.INK,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    textSize = sizeSp
    setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setLineSpacing(0f, 1.15f)
}

/** Tinted vector icon from `res/drawable`. */
internal fun Context.icon(id: Int, color: Int, sizeDp: Int = 24): Drawable =
    checkNotNull(getDrawable(id)).mutate().apply {
        setTint(color)
        setBounds(0, 0, dp(sizeDp), dp(sizeDp))
    }

internal enum class ButtonStyle { PRIMARY, SECONDARY, TONAL, DARK }

/** A full-height, sentence-case button in one of the app's styles. */
internal fun Context.styledButton(
    style: ButtonStyle,
    label: CharSequence = "",
    iconId: Int? = null,
): Button = Button(this).apply {
    text = label
    styleButton(this, style, iconId)
}

/** Restyles an existing button in place, keeping its listeners and enabled state. */
internal fun Context.styleButton(button: Button, style: ButtonStyle, iconId: Int? = null) {
    val (fill, ink, border) = when (style) {
        ButtonStyle.PRIMARY -> Triple(Ui.ACCENT, Ui.ON_ACCENT, null)
        ButtonStyle.SECONDARY -> Triple(Ui.SURFACE, Ui.INK, Ui.BORDER)
        ButtonStyle.TONAL -> Triple(Ui.ACCENT_SOFT, Ui.ACCENT, null)
        ButtonStyle.DARK -> Triple(Color.argb(40, 255, 255, 255), Color.WHITE, null)
    }
    val enabled = rounded(fill, radiusDp = 14, stroke = border)
    val disabled = rounded(
        if (style == ButtonStyle.DARK) Color.argb(18, 255, 255, 255) else Color.rgb(241, 245, 249),
        radiusDp = 14,
    )
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), disabled)
        addState(intArrayOf(), enabled)
    }
    val ripple = if (style == ButtonStyle.PRIMARY || style == ButtonStyle.DARK) {
        Color.argb(56, 255, 255, 255)
    } else {
        Color.argb(36, 15, 23, 42)
    }
    val disabledInk = if (style == ButtonStyle.DARK) Color.argb(110, 255, 255, 255) else Ui.DISABLED
    button.apply {
        background = pressable(states, ripple)
        stateListAnimator = null
        isAllCaps = false
        textSize = Ui.BODY
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(Ui.TOUCH_DP + 4)
        minimumHeight = dp(Ui.TOUCH_DP + 4)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val colors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(disabledInk, ink),
        )
        setTextColor(colors)
        // The icon rides in the text so it stays centered beside the label. A later setText()
        // replaces the label and drops the icon, which suits buttons whose action changes.
        if (iconId != null) {
            val align = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DynamicDrawableSpan.ALIGN_CENTER
            } else {
                DynamicDrawableSpan.ALIGN_BASELINE
            }
            text = SpannableStringBuilder("  ").apply {
                setSpan(ImageSpan(icon(iconId, ink, 20), align), 0, 1, 0)
                append(text)
            }
        }
    }
}

/**
 * Pads this view by the system bars on top of its own padding, so content stays clear of the
 * status and navigation bars when Android 15+ draws the app edge to edge. Before that the
 * window already sits between the bars and no insets arrive here, so the padding stays as set.
 */
@Suppress("DEPRECATION") // systemWindowInset* covers every API level from minSdk 26.
internal fun View.padForSystemBars(top: Boolean = true, bottom: Boolean = true) {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val topInset = if (top) insets.systemWindowInsetTop else 0
        val bottomInset = if (bottom) insets.systemWindowInsetBottom else 0
        v.setPadding(
            base[0] + insets.systemWindowInsetLeft,
            base[1] + topInset,
            base[2] + insets.systemWindowInsetRight,
            base[3] + bottomInset,
        )
        insets
    }
    requestApplyInsets()
}

/** Lets screen readers jump between sections (API 28+; a no-op before). */
internal fun View.markHeading() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
}
