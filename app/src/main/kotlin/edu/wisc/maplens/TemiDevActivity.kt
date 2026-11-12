package edu.wisc.maplens

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.robot.Anchor
import edu.wisc.maplens.robot.MapAlignment
import edu.wisc.maplens.robot.RobotCommand
import edu.wisc.maplens.robot.RobotEvent
import edu.wisc.maplens.robot.RobotHub
import edu.wisc.maplens.robot.RobotLink
import edu.wisc.maplens.tracking.TrackingConfig
import java.util.Locale

/**
 * Developer screen for the temi guide robot: connect (simulator or the real robot over the
 * local network), watch what it reports, send test commands, line its map up with the
 * MapLens map, mark doors, and set the pace. Navigation's "Guide with temi" uses what is set
 * here. See `docs/temi-integration.md` for the robot side.
 */
class TemiDevActivity : Activity() {
    private lateinit var graph: FloorGraph
    private lateinit var status: TextView
    private lateinit var telemetry: TextView
    private lateinit var alignmentLine: TextView
    private lateinit var connectButton: TextView
    private lateinit var hostRow: View
    private val pxPerMeter = Demo.STEP_LENGTH_PX / TrackingConfig().nominalStrideMeters
    private var lastStatus = "—"
    private var lastDetection = "—"

    private val onEvent: (RobotEvent) -> Unit = { e ->
        when (e) {
            is RobotEvent.GoToStatus ->
                lastStatus = "${e.status.name.lowercase()} ${e.description}".trim()
            is RobotEvent.Detection -> lastDetection = if (e.detected) {
                val deg = Math.toDegrees(e.angle)
                String.format(Locale.US, "person %.1f m, %.0f°", e.distance, deg)
            } else {
                "nobody"
            }
            is RobotEvent.Locations -> showLocations(e.names)
            is RobotEvent.Error -> toast(e.message)
            else -> Unit
        }
        render()
    }
    private val onState: (RobotLink.State) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RobotHub.load(this)
        graph = MapAssets.loadGraph(this)
        setContentView(buildLayout())
        RobotHub.addListener(onEvent, onState)
        RobotHub.onSimulatedSpeech = { toast(getString(R.string.temi_says, it)) }
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        RobotHub.removeListener(onEvent, onState)
    }

    private fun buildLayout(): View {
        val s = RobotHub.setup
        status = text(Ui.BODY, bold = true)
        telemetry = text(Ui.CAPTION, Ui.INK_SECONDARY)
        alignmentLine = text(Ui.CAPTION, Ui.INK_SECONDARY)
        val host = field(s.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field(s.port.toString(), InputType.TYPE_CLASS_NUMBER)
        hostRow = LinearLayout(this).apply {
            addView(host, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
            addView(
                port,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
            )
        }
        val modes = LinearLayout(this)
        fun renderModes() {
            modes.removeAllViews()
            listOf(
                true to R.string.temi_mode_sim,
                false to R.string.temi_mode_robot
            ).forEach { (sim, label) ->
                modes.addView(
                    chip(getString(label), RobotHub.setup.simulator == sim) {
                        RobotHub.update(this) { it.copy(simulator = sim) }
                        renderModes()
                        render()
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = dp(8)
                    },
                )
            }
        }
        renderModes()
        connectButton = styledButton(ButtonStyle.PRIMARY, getString(R.string.temi_connect)).apply {
            setOnClickListener {
                if (RobotHub.link != null) {
                    RobotHub.disconnect()
                } else {
                    RobotHub.update(this@TemiDevActivity) {
                        it.copy(
                            host = host.text.toString().trim(),
                            port = port.text.toString().toIntOrNull() ?: it.port,
                        )
                    }
                    RobotHub.connect(graph.nodes.getValue(graph.startNodeId).position, pxPerMeter)
                }
                render()
            }
        }

        val node = field("", InputType.TYPE_CLASS_TEXT).apply {
            hint = getString(R.string.temi_anchor_hint)
        }
        val doors = field(
            s.doorNodes.sorted().joinToString(", "),
            InputType.TYPE_CLASS_TEXT
        ).apply {
            hint = getString(R.string.temi_doors_hint)
        }
        val lead =
            field(
                s.leadMeters.toString(),
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            )
        val speed =
            field(
                s.speed.toString(),
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            addView(
                text(Ui.TITLE, bold = true).apply {
                    setText(R.string.temi_title)
                    markHeading()
                }
            )
            addView(
                text(Ui.BODY, Ui.INK_SECONDARY).apply { setText(R.string.temi_intro) },
                spaced(8)
            )

            addView(section(R.string.temi_connection), spaced(24))
            addView(
                card(modes, status, hostRow, connectButton.also { it.minHeight = dp(48) }),
                spaced(8)
            )

            addView(section(R.string.temi_robot), spaced(24))
            addView(
                card(
                    telemetry,
                    row(
                        button(R.string.temi_say_hello) {
                            RobotHub.send(RobotCommand.Speak(getString(R.string.temi_hello_line)))
                        },
                        button(R.string.temi_turn) { RobotHub.send(RobotCommand.TurnBy(180)) },
                    ),
                    row(
                        button(R.string.temi_stop) { RobotHub.send(RobotCommand.Stop) },
                        button(
                            R.string.temi_locations
                        ) { RobotHub.send(RobotCommand.ListLocations) },
                    ),
                ),
                spaced(8),
            )

            addView(section(R.string.temi_alignment), spaced(24))
            addView(
                card(
                    text(
                        Ui.CAPTION,
                        Ui.INK_SECONDARY
                    ).apply { setText(R.string.temi_alignment_help) },
                    alignmentLine,
                    node,
                    row(
                        button(
                            R.string.temi_anchor_here
                        ) { addAnchor(node.text.toString().trim()) },
                        button(R.string.temi_anchor_clear) {
                            RobotHub.update(this@TemiDevActivity) { it.copy(anchors = emptyList()) }
                            render()
                        },
                    ),
                ),
                spaced(8),
            )

            addView(section(R.string.temi_doors), spaced(24))
            addView(
                card(
                    text(Ui.CAPTION, Ui.INK_SECONDARY).apply { setText(R.string.temi_doors_help) },
                    doors,
                    text(Ui.CAPTION, Ui.INK_SECONDARY).apply { setText(R.string.temi_pace_help) },
                    row(lead, speed),
                    button(R.string.temi_save) {
                        val ids = doors.text.split(
                            ',',
                            ' '
                        ).map { it.trim() }.filter { it.isNotEmpty() }
                        val unknown = ids.filter { it !in graph.nodes }
                        if (unknown.isNotEmpty()) {
                            toast(getString(R.string.temi_unknown_nodes, unknown.joinToString()))
                            return@button
                        }
                        RobotHub.update(this@TemiDevActivity) {
                            it.copy(
                                doorNodes = ids.toSet(),
                                leadMeters = lead.text.toString().toDoubleOrNull()
                                    ?.coerceIn(1.5, 6.0) ?: it.leadMeters,
                                speed = speed.text.toString().toDoubleOrNull()
                                    ?.coerceIn(0.3, 1.0) ?: it.speed,
                            )
                        }
                        toast(getString(R.string.temi_saved))
                    },
                ),
                spaced(8),
            )
            addView(
                text(Ui.CAPTION, Ui.INK_SECONDARY).apply {
                    setText(R.string.temi_how_to_guide)
                },
                spaced(24)
            )
        }
        return ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            addView(content)
            padForSystemBars()
        }
    }

    /** Saves temi's current position as the place of graph node [id]. */
    private fun addAnchor(id: String) {
        val node = graph.nodes[id] ?: return toast(
            getString(
                R.string.temi_unknown_nodes,
                id.ifEmpty {
                    "?"
                }
            )
        )
        val pose = RobotHub.pose ?: return toast(getString(R.string.temi_no_pose))
        RobotHub.update(this) { s ->
            s.copy(
                anchors = s.anchors.filter { it.label != id } +
                    Anchor(id, node.position, pose.x, pose.y)
            )
        }
        render()
    }

    private fun showLocations(names: List<String>) {
        if (names.isEmpty()) return toast(getString(R.string.temi_no_locations))
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.temi_locations)
            .setItems(names.toTypedArray()) { _, i ->
                RobotHub.send(RobotCommand.GoToLocation(names[i], RobotHub.setup.speed))
            }
            .show()
    }

    private fun render() {
        val link = RobotHub.link
        val state = link?.state ?: RobotLink.State.DISCONNECTED
        status.text = when (state) {
            RobotLink.State.CONNECTED -> getString(R.string.temi_status_connected, link?.name ?: "")
            RobotLink.State.CONNECTING -> getString(R.string.temi_status_connecting)
            RobotLink.State.DISCONNECTED ->
                RobotHub.lastError ?: getString(R.string.temi_status_off)
        }
        status.setTextColor(if (state == RobotLink.State.CONNECTED) Ui.ACCENT else Ui.INK)
        connectButton.setText(if (link != null) R.string.temi_disconnect else R.string.temi_connect)
        hostRow.visibility = if (RobotHub.setup.simulator) View.GONE else View.VISIBLE
        val pose = RobotHub.pose
        val battery = RobotHub.hello?.battery?.let { "$it %" } ?: "—"
        telemetry.text = getString(
            R.string.temi_telemetry,
            pose?.let {
                val deg = Math.toDegrees(it.yaw)
                String.format(Locale.US, "x %.2f, y %.2f m, yaw %.0f°", it.x, it.y, deg)
            } ?: "—",
            battery,
            lastStatus,
            lastDetection,
        )
        val anchors = RobotHub.setup.anchors
        val fit = MapAlignment.fit(anchors)
        alignmentLine.text = if (RobotHub.setup.simulator) {
            getString(R.string.temi_alignment_sim)
        } else if (fit == null) {
            getString(R.string.temi_alignment_none, anchors.size, anchors.joinToString { it.label })
        } else {
            getString(
                R.string.temi_alignment_fit,
                anchors.size,
                anchors.joinToString { it.label },
                fit.rmsPx / fit.scale,
            )
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun section(title: Int) = text(Ui.CAPTION, Ui.INK_SECONDARY, bold = true).apply {
        setText(title)
        letterSpacing = 0.08f
        isAllCaps = true
    }

    private fun card(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.SURFACE, stroke = Ui.BORDER)
        setPadding(dp(16), dp(12), dp(16), dp(16))
        views.forEachIndexed { i, v -> addView(v, spaced(if (i == 0) 0 else 12)) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        views.forEachIndexed { i, v ->
            addView(
                v,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                },
            )
        }
    }

    private fun button(label: Int, onTap: () -> Unit) =
        styledButton(ButtonStyle.TONAL, getString(label)).apply {
            minHeight = dp(48)
            setOnClickListener { onTap() }
        }

    private fun chip(label: String, selected: Boolean, onTap: () -> Unit) =
        text(Ui.CAPTION, if (selected) Ui.ACCENT else Ui.INK_SECONDARY, bold = true).apply {
            text = label
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(16), 0, dp(16), 0)
            background = pressable(
                if (selected) {
                    rounded(Ui.ACCENT_SOFT, radiusDp = 20)
                } else {
                    rounded(Ui.SURFACE, radiusDp = 20, stroke = Ui.BORDER)
                },
            )
            setOnClickListener { onTap() }
        }

    private fun field(value: String, type: Int) = EditText(this).apply {
        setText(value)
        inputType = type
        textSize = Ui.BODY
        setSingleLine()
        minHeight = dp(48)
    }
}
