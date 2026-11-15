package edu.wisc.maplens

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * User mode is the app as a real user gets it: scan, save, navigate. Developer mode adds the
 * team's tools: the Developer list on the home screen, Edit map after a scan, the Fast /
 * Normal parser switch and the demo walk. Remembered on the phone; new installs start in user
 * mode. Five quick taps on the home screen's logo offer developer mode.
 */
internal object AppMode {
    private const val PREFS = "app_mode"
    private const val KEY_DEVELOPER = "developer"

    fun isDeveloper(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEVELOPER, false)

    fun setDeveloper(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEVELOPER, on).apply()
    }
}

/**
 * Asks whether to turn on developer mode: an icon, what it adds, then Turn on or Cancel.
 * [onYes] runs only on Turn on.
 */
internal fun Context.confirmDeveloperMode(onYes: () -> Unit) {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(4))
        addView(
            FrameLayout(context).apply {
                background = rounded(Ui.ACCENT_SOFT, radiusDp = 24)
                addView(
                    ImageView(context).apply {
                        setImageDrawable(icon(R.drawable.ic_code, Ui.ACCENT))
                        scaleType = ImageView.ScaleType.CENTER
                    },
                    FrameLayout.LayoutParams(-1, -1),
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
        addView(
            text(Ui.HEADING, bold = true).apply {
                setText(R.string.mode_confirm_title)
                gravity = Gravity.CENTER
                markHeading()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
        )
        addView(
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                setText(R.string.mode_confirm_body)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }
    AlertDialog.Builder(this)
        .setView(content)
        .setPositiveButton(R.string.mode_confirm_on) { _, _ -> onYes() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
