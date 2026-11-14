package edu.wisc.maplens

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * The app's entry. Scanning a poster is the one primary action, in the thumb zone. Saved maps
 * come first once there are any (pinned ones on top), each with a menu to rename, pin or
 * delete it; the introduction shows only before the first map. An unfinished scan, the bundled
 * EH3 map and the debug tools sit under Developer.
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
            val saved = MapAssets.savedMaps(this@HomeActivity).list()
            if (saved.isEmpty()) {
                // First run: explain the flow before the camera.
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
            } else {
                // Returning: the maps are what people came for.
                addView(
                    text(Ui.BODY, Ui.INK_SECONDARY).apply { setText(R.string.home_pick_map) },
                    spaced(top = 16),
                )
                val (pinned, others) = saved.partition { it.pinned }
                if (pinned.isNotEmpty()) {
                    addView(sectionTitle(R.string.home_pinned), spaced(top = 24))
                    pinned.forEachIndexed { i, map ->
                        addView(savedRow(map), spaced(top = if (i == 0) 12 else 8))
                    }
                }
                if (others.isNotEmpty()) {
                    addView(
                        sectionTitle(
                            if (pinned.isEmpty()) R.string.home_maps else R.string.home_other_maps,
                        ),
                        spaced(top = 24),
                    )
                    others.forEachIndexed { i, map ->
                        addView(savedRow(map), spaced(top = if (i == 0) 12 else 8))
                    }
                }
            }
            addView(sectionTitle(R.string.home_developer), spaced(top = 32))
            // A scan left mid-check: picked up from here, not mixed in with finished maps.
            if (DraftStore.of(this@HomeActivity).exists) {
                addView(
                    devRow(R.drawable.ic_edit, R.string.home_draft, R.string.home_draft_detail) {
                        startActivity(Intent(this@HomeActivity, GraphEditorActivity::class.java))
                    },
                    spaced(top = 12),
                )
            }
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
                devRow(
                    R.drawable.ic_robot,
                    R.string.home_dev_temi,
                    R.string.home_dev_temi_detail,
                ) { startActivity(Intent(this@HomeActivity, TemiDevActivity::class.java)) },
                spaced(top = 8),
            )
            debugIntent(FIXTURE_EDITOR)?.let { editor ->
                addView(
                    devRow(
                        R.drawable.ic_edit,
                        R.string.home_dev_edit,
                        R.string.home_dev_edit_detail,
                    ) { startActivity(editor) },
                    spaced(top = 8),
                )
            }
            debugIntent(RECORDER)?.let { recorder ->
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
            // The launcher icon itself, so the brand mark matches the home-screen icon. The
            // adaptive icon draws through the device's own mask shape.
            ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
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

    /**
     * A saved map: tap to navigate on it. Its ⋮ button (or a long press) offers Rename,
     * Pin / Unpin and Delete.
     */
    private fun savedRow(map: SavedMap): View {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(map.savedAtMillis))
        val detail = getString(R.string.home_map_detail, map.rooms, date)
        val icon = if (map.pinned) R.drawable.ic_push_pin else R.drawable.ic_map
        lateinit var row: View
        val more = ImageButton(this).apply {
            setImageDrawable(icon(R.drawable.ic_more_vert, Ui.INK_SECONDARY))
            background = pressable(rounded(android.graphics.Color.TRANSPARENT, radiusDp = 24))
            contentDescription = getString(R.string.home_map_actions, map.name)
            setOnClickListener { showMapMenu(it, map) }
        }
        row = devRow(icon, map.name, detail, trailing = more) {
            startActivity(IndoorNavActivity.open(this, map.id))
        }.apply {
            setOnLongClickListener {
                showMapMenu(more, map)
                true
            }
        }
        return row
    }

    private fun showMapMenu(anchor: View, map: SavedMap) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_RENAME, 0, R.string.home_map_rename)
        menu.menu.add(
            0,
            MENU_PIN,
            1,
            if (map.pinned) R.string.home_map_unpin else R.string.home_map_pin,
        )
        menu.menu.add(0, MENU_DELETE, 2, R.string.home_map_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> rename(map)
                MENU_PIN -> {
                    MapAssets.savedMaps(this).setPinned(map.id, !map.pinned)
                    setContentView(buildLayout())
                }
                MENU_DELETE -> confirmDelete(map)
            }
            true
        }
        menu.show()
    }

    private fun rename(map: SavedMap) {
        val field = EditText(this).apply {
            setText(map.name)
            setSelectAllOnFocus(true)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.home_map_name_hint)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.home_map_rename)
            .setView(box)
            .setPositiveButton(R.string.home_map_rename_save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) {
                    MapAssets.savedMaps(this).rename(map.id, name)
                    setContentView(buildLayout())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
        )
        dialog.show()
        field.requestFocus()
    }

    private fun confirmDelete(map: SavedMap) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.home_map_delete_title, map.name))
            .setMessage(R.string.home_map_delete_body)
            .setPositiveButton(R.string.home_map_delete) { _, _ ->
                MapAssets.savedMaps(this).delete(map.id)
                setContentView(buildLayout())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun devRow(iconId: Int, title: Int, detail: Int, onTap: () -> Unit): View =
        devRow(iconId, getString(title), getString(detail), onTap = onTap)

    /** A full-width tappable row: icon tile, title and detail, then [trailing] or a chevron. */
    private fun devRow(
        iconId: Int,
        title: String,
        detail: String,
        trailing: View? = null,
        onTap: () -> Unit,
    ): View =
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
                            // Saved maps keep their whole name: two lines before it is cut.
                            maxLines = if (trailing != null) 2 else 1
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
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(dp(48), dp(48)))
            } else {
                addView(
                    ImageView(context).apply {
                        setImageDrawable(icon(R.drawable.ic_chevron_right, Ui.INK_SECONDARY))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) },
                )
            }
        }

    /** A debug-only screen, when this build has it (they live in the debug source set). */
    private fun debugIntent(name: String): Intent? {
        val intent = Intent().setComponent(ComponentName(this, name))
        return intent.takeIf { packageManager.resolveActivity(it, 0) != null }
    }

    private fun spaced(top: Int, wrap: Boolean = false) =
        LinearLayout.LayoutParams(if (wrap) WRAP else MATCH, WRAP).apply { topMargin = dp(top) }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val RECORDER = "edu.wisc.maplens.debug.RecorderActivity"
        const val FIXTURE_EDITOR = "edu.wisc.maplens.debug.EditFixtureActivity"
        const val MENU_RENAME = 1
        const val MENU_PIN = 2
        const val MENU_DELETE = 3
    }
}
