package edu.wisc.maplens

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * User mode is the app as a real user gets it: scan, save, navigate. Developer mode adds the
 * team's tools: the Developer list on the home screen, Edit map after a scan, and the Fast /
 * Normal parser switch. Remembered on the phone; new installs start in user mode.
 */
internal object AppMode {
    private const val PREFS = "app_mode"
    private const val KEY_DEVELOPER = "developer"

    /**
     * Keeps casual taps out, nothing more: it ships in the app. Change it here; a real lock
     * would need an account or a server, and the app is offline.
     */
    private const val PASSWORD = "12345678"

    fun isDeveloper(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEVELOPER, false)

    fun setDeveloper(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEVELOPER, on).apply()
    }

    fun accepts(password: String): Boolean = password == PASSWORD
}

/**
 * Asks for the developer password in a dialog: a lock icon, one password field with a show
 * toggle, and an inline error that shakes the field. [onUnlocked] runs once the password is
 * right; Cancel or Back leaves user mode on.
 */
internal fun Context.askDeveloperPassword(onUnlocked: () -> Unit) {
    val error = TextView(this).apply {
        textSize = Ui.CAPTION
        setTextColor(ERROR_RED)
        visibility = View.INVISIBLE
        setText(R.string.mode_password_wrong)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
    }
    val field = EditText(this).apply {
        hint = getString(R.string.mode_password_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        textSize = Ui.BODY
        setTextColor(Ui.INK)
        background = rounded(Ui.BACKGROUND, radiusDp = 12, stroke = Ui.BORDER)
        setPadding(dp(16), dp(14), dp(56), dp(14))
        contentDescription = getString(R.string.mode_password_hint)
    }
    val reveal = ImageView(this).apply {
        setImageDrawable(icon(R.drawable.ic_visibility, Ui.INK_SECONDARY))
        scaleType = ImageView.ScaleType.CENTER
        background = pressable(rounded(android.graphics.Color.TRANSPARENT, radiusDp = 24))
        contentDescription = getString(R.string.mode_password_show)
        isClickable = true
        setOnClickListener {
            val shown = field.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ==
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            val cursor = field.selectionEnd
            field.inputType = InputType.TYPE_CLASS_TEXT or if (shown) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            field.setSelection(cursor.coerceIn(0, field.length()))
            setImageDrawable(
                icon(
                    if (shown) R.drawable.ic_visibility else R.drawable.ic_visibility_off,
                    Ui.INK_SECONDARY,
                ),
            )
            contentDescription = getString(
                if (shown) R.string.mode_password_show else R.string.mode_password_hide,
            )
        }
    }
    val fieldBox = FrameLayout(this).apply {
        addView(field, FrameLayout.LayoutParams(-1, -2))
        addView(
            reveal,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { marginEnd = dp(4) },
        )
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(8))
        addView(
            FrameLayout(context).apply {
                background = rounded(Ui.ACCENT_SOFT, radiusDp = 24)
                addView(
                    ImageView(context).apply {
                        setImageDrawable(icon(R.drawable.ic_lock, Ui.ACCENT))
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
                setText(R.string.mode_password_title)
                gravity = Gravity.CENTER
                markHeading()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
        )
        addView(
            text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                setText(R.string.mode_password_body)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        addView(fieldBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        addView(
            error,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                marginStart = dp(4)
            },
        )
    }
    val dialog = AlertDialog.Builder(this)
        .setView(content)
        .setPositiveButton(R.string.mode_password_unlock, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    fun submit() {
        if (AppMode.accepts(field.text.toString())) {
            field.performHapticFeedback(
                HapticFeedbackConstants.CONFIRM.takeIf {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                } ?: HapticFeedbackConstants.VIRTUAL_KEY
            )
            dialog.dismiss()
            onUnlocked()
            return
        }
        error.visibility = View.VISIBLE
        field.background = rounded(Ui.BACKGROUND, radiusDp = 12, stroke = ERROR_RED)
        field.selectAll()
        field.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            },
        )
        // A short side-to-side shake, as a lock screen does for a wrong PIN.
        val step = dpf(8f)
        ObjectAnimator.ofFloat(
            fieldBox,
            View.TRANSLATION_X,
            0f,
            -step,
            step,
            -step / 2,
            step / 2,
            0f
        )
            .setDuration(SHAKE_MS).start()
    }
    field.setOnEditorActionListener { _, action, _ ->
        if (action == EditorInfo.IME_ACTION_DONE) submit()
        true
    }
    dialog.setOnShowListener {
        val unlock = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        unlock.isEnabled = false
        unlock.setOnClickListener { submit() }
        field.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    unlock.isEnabled = !s.isNullOrEmpty()
                    if (error.visibility == View.VISIBLE) {
                        error.visibility = View.INVISIBLE
                        field.background = rounded(Ui.BACKGROUND, radiusDp = 12, stroke = Ui.ACCENT)
                    }
                }
            },
        )
        field.requestFocus()
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
}

private val ERROR_RED = android.graphics.Color.rgb(220, 38, 38)
private const val SHAKE_MS = 320L
