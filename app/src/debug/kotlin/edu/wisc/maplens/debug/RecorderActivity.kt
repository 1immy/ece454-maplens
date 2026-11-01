package edu.wisc.maplens.debug

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import edu.wisc.maplens.R
import edu.wisc.maplens.tracking.RecorderSnapshot
import edu.wisc.maplens.tracking.SensorTraceRecorder
import edu.wisc.maplens.tracking.TraceSensor
import edu.wisc.maplens.tracking.checkpointLabel
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Developer screen for recording walking traces (#11). Debug source set only: release builds
 * get no entry point, resources or permission prompts from it.
 *
 * The Activity is a thin view over [SensorTraceRecorder]: buttons post commands, and
 * [render] redraws everything from each [RecorderSnapshot]. It holds no recording state of its
 * own, so the screen can never disagree with what is being written.
 *
 * The debug manifest handles rotation itself (`configChanges`), so turning the phone doesn't
 * recreate the Activity and interrupt a recording; leaving the screen saves the trace.
 */
class RecorderActivity : Activity() {
    private lateinit var recorder: SensorTraceRecorder

    /** One reading card per stream, filled in by [render]. */
    private val cards = mutableMapOf<TraceSensor, View>()

    /** Copies exports off the main thread; a long trace can take a moment. */
    private val exportWorker = Executors.newSingleThreadExecutor()

    /** Last state received, used to re-render after local UI changes such as typing. */
    private var snapshot: RecorderSnapshot? = null

    /** File chosen for export while the system file picker is open; survives recreation. */
    private var exportSource: File? = null

    /** True while an export copy runs; recording and deleting are disabled meanwhile. */
    private var exportBusy = false
    private var lastDisplayRotation = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.recorder_activity)
        findViewById<View>(R.id.recorder_root).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        val container = findViewById<LinearLayout>(R.id.recorder_sensors)
        TraceSensor.entries.forEach { kind ->
            val card = layoutInflater.inflate(R.layout.recorder_sensor, container, false)
            card.findViewById<TextView>(R.id.sensor_title).setText(title(kind))
            if (kind == TraceSensor.STEP_COUNTER) {
                card.findViewById<TextView>(R.id.sensor_note).apply {
                    visibility = View.VISIBLE
                    setText(R.string.recorder_counter_note)
                }
            }
            cards[kind] = card
            container.addView(card)
        }
        exportSource = savedInstanceState?.getString("exportSource")?.let(::File)
        recorder = SensorTraceRecorder(this, File(filesDir, "recordings"), onSnapshot = ::render)
        findViewById<Button>(R.id.recorder_align).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.recorder_align)
                .setItems(R.array.recorder_map_directions) { _, choice ->
                    // Up, right, down, left on the y-down map: atan2(dy, dx) of each direction.
                    val bearings = doubleArrayOf(-Math.PI / 2, 0.0, Math.PI / 2, Math.PI)
                    recorder.alignHeading(bearings[choice])
                }.show()
        }
        findViewById<Switch>(R.id.recorder_peak_mode).setOnCheckedChangeListener { _, enabled ->
            if (snapshot?.usePeaks != enabled) recorder.useAccelerometerSteps(enabled)
        }
        findViewById<Button>(R.id.recorder_record).setOnClickListener {
            if (snapshot?.recording == true) recorder.stop() else recorder.start()
        }
        // Checkpoints: type the next door's number while walking, tap Mark when passing it.
        val room = findViewById<EditText>(R.id.recorder_room)
        val mark = findViewById<Button>(R.id.recorder_checkpoint)
        mark.setOnClickListener {
            val label = checkpointLabel(room.text.toString()) ?: return@setOnClickListener
            recorder.checkpoint(label)
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            // Clearing also blocks an accidental double tap on the same door.
            room.text.clear()
        }
        // The keyboard's Done key marks too. performClick() ignores isEnabled, hence the check.
        room.setOnEditorActionListener { _, _, _ ->
            if (mark.isEnabled) mark.performClick()
            true
        }
        // Re-render on each keystroke so Mark enables only for a valid room number.
        room.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                snapshot?.let(::render)
            }
        })
        findViewById<Button>(R.id.recorder_permission).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.recorder_permission_reason)
                .setPositiveButton(R.string.recorder_permission) { _, _ ->
                    if (Build.VERSION.SDK_INT >= 29) {
                        requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
                    }
                }.setNegativeButton(R.string.recorder_cancel, null).show()
        }
        findViewById<Button>(R.id.recorder_export).setOnClickListener { export(false) }
        findViewById<Button>(R.id.recorder_details).setOnClickListener { export(true) }
        findViewById<Button>(R.id.recorder_delete).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.recorder_delete_question)
                .setPositiveButton(R.string.recorder_delete) { _, _ -> recorder.deleteRecordings() }
                .setNegativeButton(R.string.recorder_cancel, null).show()
        }
    }

    override fun onResume() {
        super.onResume()
        recorder.resume()
        updateDisplayRotation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateDisplayRotation()
    }

    /** Tells the recorder which screen edge is "up", so heading follows landscape use. */
    @Suppress("DEPRECATION")
    private fun updateDisplayRotation() {
        val rotation = windowManager.defaultDisplay.rotation
        if (rotation != lastDisplayRotation) {
            lastDisplayRotation = rotation
            recorder.setScreenRotation(rotation)
        }
    }

    override fun onPause() {
        recorder.pause()
        super.onPause()
    }

    override fun onDestroy() {
        recorder.close()
        exportWorker.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportSource", exportSource?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        // Restart registrations even if the permission dialog didn't pause this Activity.
        recorder.pause()
        recorder.resume()
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.recorder_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    /** Redraws the whole screen from [state]; called on the main thread for every snapshot. */
    private fun render(state: RecorderSnapshot) {
        if (isDestroyed) return
        // A 180-degree change need not produce an orientation Configuration change.
        updateDisplayRotation()
        snapshot = state
        if (state.recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val seconds = state.elapsedMs / 1000
        val clock = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        findViewById<TextView>(R.id.recorder_summary).text = getString(
            R.string.recorder_summary, clock, state.steps, state.rows,
        )
        findViewById<TextView>(R.id.recorder_status).text = state.message
        findViewById<Switch>(R.id.recorder_peak_mode).apply {
            isChecked = state.usePeaks
            isEnabled = !state.recording && !exportBusy
        }
        findViewById<Button>(R.id.recorder_align).isEnabled =
            !state.recording && state.heading != null && !exportBusy
        val heading = state.heading
        val mapHeading = heading?.mapRad
        findViewById<TextView>(R.id.recorder_heading).text = when {
            heading == null -> getString(R.string.recorder_heading_unavailable)
            mapHeading == null -> getString(R.string.recorder_heading_unaligned)
            else -> getString(
                R.string.recorder_heading_value,
                (Math.toDegrees(mapHeading) + 360).roundToInt() % 360
            )
        }
        findViewById<TextView>(R.id.recorder_step_comparison).text = getString(
            R.string.recorder_step_comparison,
            state.detectorSteps,
            state.peakSteps,
            state.skippedSteps,
            state.readings.firstOrNull { it.kind == TraceSensor.STEP_COUNTER }
                ?.values?.firstOrNull()?.toLong()?.toString()
                ?: getString(R.string.recorder_waiting_counter),
        )
        findViewById<Button>(R.id.recorder_record).apply {
            setText(if (state.recording) R.string.recorder_stop else R.string.recorder_start)
            isEnabled = !exportBusy
            backgroundTintList = ColorStateList.valueOf(
                getColor(
                    if (state.recording) R.color.recorder_danger else R.color.recorder_accent,
                )
            )
        }
        val room = findViewById<EditText>(R.id.recorder_room)
        room.isEnabled = state.recording
        findViewById<Button>(R.id.recorder_checkpoint).isEnabled =
            state.recording && checkpointLabel(room.text.toString()) != null
        findViewById<TextView>(R.id.recorder_checkpoints).text =
            state.lastCheckpoint?.let {
                getString(R.string.recorder_checkpoint_count, state.checkpoints, it)
            } ?: getString(R.string.recorder_checkpoint_note)
        findViewById<Button>(R.id.recorder_permission).apply {
            visibility = if (Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            isEnabled = !state.recording
        }
        findViewById<TextView>(R.id.recorder_saved).text = state.latestFile?.let {
            val file = getString(R.string.recorder_saved, it.name, (it.length() + 1023) / 1024)
            if (state.savedSteps == null) {
                file
            } else {
                file + "\n" + getString(
                    R.string.recorder_saved_counts,
                    state.savedSteps,
                    state.savedDetectorSteps,
                    state.savedPeakSteps?.toString() ?: getString(R.string.recorder_not_measured),
                )
            }
        } ?: getString(R.string.recorder_no_trace)
        listOf(R.id.recorder_export, R.id.recorder_details).forEach {
            findViewById<Button>(it).isEnabled =
                state.latestFile != null && !state.recording && !exportBusy
        }
        findViewById<Button>(R.id.recorder_delete).isEnabled = !state.recording && !exportBusy
        state.readings.forEach { reading ->
            val card = cards.getValue(reading.kind)
            val rate = if (reading.kind == TraceSensor.STEP ||
                reading.kind == TraceSensor.STEP_COUNTER
            ) {
                "NORMAL / event-driven"
            } else {
                "GAME / ~50 Hz"
            }
            card.findViewById<TextView>(R.id.sensor_status).text =
                if (reading.timestampNs == 0L) {
                    getString(R.string.recorder_sensor_status, reading.status, rate)
                } else {
                    val age = (SystemClock.elapsedRealtimeNanos() - reading.timestampNs) / 1e9
                    getString(R.string.recorder_sensor_live, rate, reading.hz, age)
                }
            val axes = when (reading.kind) {
                TraceSensor.ACCEL, TraceSensor.GYRO -> listOf("x", "y", "z")
                TraceSensor.GAME_RV -> listOf("x", "y", "z", "w")
                TraceSensor.STEP -> listOf("event")
                TraceSensor.STEP_COUNTER -> listOf("steps")
            }
            val units = when (reading.kind) {
                TraceSensor.ACCEL -> " m/s²"
                TraceSensor.GYRO -> " rad/s"
                else -> ""
            }
            card.findViewById<TextView>(R.id.sensor_values).text =
                if (reading.values.isEmpty()) {
                    "—"
                } else {
                    reading.values.mapIndexed { index, value ->
                        String.format(Locale.getDefault(), "%s  % .3f%s", axes[index], value, units)
                    }.joinToString("\n")
                }
        }
    }

    /**
     * Opens the system "save as" picker for the latest trace's CSV, or its JSON if [details].
     * The copy happens in [onActivityResult]; the private original is kept either way.
     */
    private fun export(details: Boolean) {
        val latest = snapshot?.latestFile ?: return
        if (snapshot?.recording == true || exportBusy) return
        exportSource = if (details) {
            File(latest.parentFile, latest.nameWithoutExtension + ".json")
        } else {
            latest
        }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (details) "application/json" else "text/csv"
                putExtra(Intent.EXTRA_TITLE, exportSource!!.name)
            },
            2,
        )
    }

    @Deprecated("Native Activity result API; no additional UI dependencies required")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2) return
        val source = exportSource ?: return
        exportSource = null
        val destination = data?.data
        if (resultCode != RESULT_OK || destination == null) {
            Toast.makeText(this, R.string.recorder_export_cancelled, Toast.LENGTH_SHORT).show()
            return
        }
        exportBusy = true
        snapshot?.let(::render)
        exportWorker.execute {
            val result = try {
                source.inputStream().use { input ->
                    checkNotNull(contentResolver.openOutputStream(destination, "wt")).use {
                        input.copyTo(it)
                    }
                }
                getString(R.string.recorder_exported)
            } catch (error: Exception) {
                getString(R.string.recorder_export_failed, error.message)
            }
            runOnUiThread {
                exportBusy = false
                if (!isDestroyed) {
                    snapshot?.let(::render)
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun title(kind: TraceSensor): Int = when (kind) {
        TraceSensor.STEP -> R.string.recorder_step
        TraceSensor.STEP_COUNTER -> R.string.recorder_counter
        TraceSensor.ACCEL -> R.string.recorder_accel
        TraceSensor.GYRO -> R.string.recorder_gyro
        TraceSensor.GAME_RV -> R.string.recorder_rotation
    }
}
