package edu.wisc.maplens.debug

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import edu.wisc.maplens.R
import edu.wisc.maplens.tracking.RecorderSnapshot
import edu.wisc.maplens.tracking.SensorTraceRecorder
import edu.wisc.maplens.tracking.TraceSensor
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Debug source set only: no entry point, resources or permission prompts in release. */
class RecorderActivity : Activity() {
    private lateinit var recorder: SensorTraceRecorder
    private val cards = mutableMapOf<TraceSensor, View>()
    private val exportWorker = Executors.newSingleThreadExecutor()
    private var snapshot: RecorderSnapshot? = null
    private var exportSource: File? = null
    private var exportBusy = false

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
        recorder = SensorTraceRecorder(this, File(filesDir, "recordings")) { render(it) }
        findViewById<Button>(R.id.recorder_record).setOnClickListener {
            if (snapshot?.recording == true) recorder.stop() else recorder.start()
        }
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

    private fun render(state: RecorderSnapshot) {
        if (isDestroyed) return
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
        findViewById<Button>(R.id.recorder_record).apply {
            setText(if (state.recording) R.string.recorder_stop else R.string.recorder_start)
            isEnabled = !exportBusy
            backgroundTintList = ColorStateList.valueOf(
                getColor(
                    if (state.recording) R.color.recorder_danger else R.color.recorder_accent,
                )
            )
        }
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
            getString(R.string.recorder_saved, it.name, (it.length() + 1023) / 1024)
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
