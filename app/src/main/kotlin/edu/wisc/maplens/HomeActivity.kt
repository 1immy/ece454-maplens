package edu.wisc.maplens

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * The app's entry. Scanning a poster is the one primary action, in the thumb zone. Maps saved
 * after a scan are listed under Your maps (#65); the bundled EH3 navigation screen
 * ([MainActivity]) and the debug recorder sit under Developer.
 */
class HomeActivity : Activity() {
    // Built on resume so a map saved in the capture screen shows when the user comes back.
    override fun onResume() {
        super.onResume()
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(brand())
            addView(
                text(Ui.TITLE, bold = true).apply {
                    setText(R.string.home_title)
                    markHeading()
                },
                spaced(top = 24),
            )
            addView(
                text(Ui.BODY, Ui.INK_SECONDARY).apply { setText(R.string.home_intro) },
                spaced(top = 12),
            )
            addView(offlinePill(), spaced(top = 16, wrap = true))
            addView(steps(), spaced(top = 24))
            val saved = MapAssets.savedMaps(this@HomeActivity).list()
            if (saved.isNotEmpty()) {
                addView(sectionTitle(R.string.home_maps), spaced(top = 32))
                saved.forEachIndexed { i, map ->
                    addView(savedRow(map), spaced(top = if (i == 0) 12 else 8))
                }
            }
            addView(sectionTitle(R.string.home_developer), spaced(top = 32))
            addView(
                devRow(
                    R.drawable.ic_navigation,
                    R.string.home_dev_nav,
                    R.string.home_dev_nav_detail
                ) {
                    startActivity(Intent(this@HomeActivity, IndoorNavActivity::class.java))
                },
                spaced(top = 12),
            )
            addView(
                devRow(R.drawable.ic_map, R.string.home_dev_map, R.string.home_dev_map_detail) {
                    startActivity(Intent(this@HomeActivity, MainActivity::class.java))
                },
                spaced(top = 8),
            )
            recorderIntent()?.let { recorder ->
                addView(
                    devRow(
                        R.drawable.ic_walk,
                        R.string.home_dev_recorder,
                        R.string.home_dev_recorder_detail,
                    ) { startActivity(recorder) },
                    spaced(top = 8),
                )
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        val scan = styledButton(
            ButtonStyle.PRIMARY,
            getString(R.string.home_scan),
            R.drawable.ic_camera,
        ).apply {
            minHeight = dp(56)
            minimumHeight = dp(56)
            contentDescription = getString(R.string.home_scan)
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, PosterCaptureActivity::class.java))
            }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BACKGROUND)
            setPadding(dp(24), dp(12), dp(24), dp(16))
            addView(
                View(context).apply { setBackgroundColor(Ui.BORDER) },
                LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                    leftMargin = -dp(24)
                    rightMargin = -dp(24)
                    bottomMargin = dp(12)
                },
            )
            addView(scan, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(
                text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                    setText(R.string.home_scan_hint)
                    gravity = Gravity.CENTER
                },
                spaced(top = 8),
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BACKGROUND)
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
            padForSystemBars()
        }
    }

    private fun brand(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(context).apply {
                setImageDrawable(icon(R.drawable.ic_map, Ui.ON_ACCENT, 20))
                scaleType = ImageView.ScaleType.CENTER
                background = rounded(Ui.ACCENT, radiusDp = 10)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        addView(
            text(Ui.HEADING, bold = true).apply { setText(R.string.app_name) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) },
        )
    }

    private fun offlinePill(): View = TextView(this).apply {
        setText(R.string.home_offline)
        textSize = Ui.CAPTION
        setTextColor(Ui.ACCENT)
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Ui.ACCENT_SOFT, radiusDp = 16)
        setPadding(dp(12), dp(6), dp(14), dp(6))
        setCompoundDrawablesRelative(icon(R.drawable.ic_wifi_off, Ui.ACCENT, 16), null, null, null)
        compoundDrawablePadding = dp(8)
    }

    /** The three stages a user goes through, numbered, so the flow is clear before the camera. */
    private fun steps(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.SURFACE, stroke = Ui.BORDER)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        addView(
            text(Ui.BODY, bold = true).apply {
                setText(R.string.home_how)
                markHeading()
            },
        )
        val items = listOf(
            R.string.home_step_photo to R.string.home_step_photo_detail,
            R.string.home_step_check to R.string.home_step_check_detail,
            R.string.home_step_walk to R.string.home_step_walk_detail,
        )
        items.forEachIndexed { i, (title, detail) ->
            addView(step(i + 1, title, detail), spaced(top = 16))
        }
    }

    private fun step(number: Int, title: Int, detail: Int): View = LinearLayout(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "${getString(R.string.home_step_number, number)}. " +
            "${getString(title)}. ${getString(detail)}"
        addView(
            text(Ui.CAPTION, Ui.ACCENT, bold = true).apply {
                text = NumberFormat.getIntegerInstance().format(number)
                gravity = Gravity.CENTER
                background = rounded(Ui.ACCENT_SOFT, radiusDp = 14)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(28), dp(28)),
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(text(Ui.BODY, bold = true).apply { setText(title) })
                addView(
                    text(Ui.CAPTION, Ui.INK_SECONDARY).apply { setText(detail) },
                    spaced(top = 2),
                )
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) },
        )
    }

    private fun sectionTitle(title: Int): View =
        text(Ui.CAPTION, Ui.INK_SECONDARY, bold = true).apply {
            setText(title)
            letterSpacing = 0.08f
            isAllCaps = true
            markHeading()
        }

    /** A saved map: tap to navigate on it, long-press to delete it. */
    private fun savedRow(map: SavedMap): View {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(map.savedAtMillis))
        val detail = getString(R.string.home_map_detail, map.rooms, date)
        return devRow(R.drawable.ic_map, map.name, detail) {
            startActivity(MainActivity.open(this, map.id))
        }.apply {
            setOnLongClickListener {
                AlertDialog.Builder(this@HomeActivity)
                    .setTitle(getString(R.string.home_map_delete_title, map.name))
                    .setPositiveButton(R.string.home_map_delete) { _, _ ->
                        MapAssets.savedMaps(this@HomeActivity).delete(map.id)
                        setContentView(buildLayout())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
    }

    private fun devRow(iconId: Int, title: Int, detail: Int, onTap: () -> Unit): View =
        devRow(iconId, getString(title), getString(detail), onTap)

    /** A full-width tappable row: icon tile, title and detail, chevron. */
    private fun devRow(iconId: Int, title: String, detail: String, onTap: () -> Unit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = pressable(rounded(Ui.SURFACE, stroke = Ui.BORDER))
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $detail"
            setOnClickListener { onTap() }
            addView(
                FrameLayout(context).apply {
                    background = rounded(Ui.BACKGROUND, radiusDp = 12)
                    addView(
                        ImageView(context).apply {
                            setImageDrawable(icon(iconId, Ui.INK_SECONDARY))
                            scaleType = ImageView.ScaleType.CENTER
                        },
                        FrameLayout.LayoutParams(MATCH, MATCH),
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(
                        text(Ui.BODY, bold = true).apply {
                            text = title
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                    )
                    addView(
                        text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                            text = detail
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        spaced(top = 2),
                    )
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) },
            )
            addView(
                ImageView(context).apply {
                    setImageDrawable(icon(R.drawable.ic_chevron_right, Ui.INK_SECONDARY))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) },
            )
        }

    /** The debug-only recorder, when this build has it (it lives in the debug source set). */
    private fun recorderIntent(): Intent? {
        val intent = Intent().setComponent(ComponentName(this, RECORDER))
        return intent.takeIf { packageManager.resolveActivity(it, 0) != null }
    }

    private fun spaced(top: Int, wrap: Boolean = false) =
        LinearLayout.LayoutParams(if (wrap) WRAP else MATCH, WRAP).apply { topMargin = dp(top) }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val RECORDER = "edu.wisc.maplens.debug.RecorderActivity"
    }
}
