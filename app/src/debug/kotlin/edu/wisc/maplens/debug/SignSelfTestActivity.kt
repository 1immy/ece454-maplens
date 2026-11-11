package edu.wisc.maplens.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import edu.wisc.maplens.MapAssets
import edu.wisc.maplens.nav.SignReader
import edu.wisc.maplens.nav.TextLine
import edu.wisc.maplens.tracking.TrackingConfig
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.tan

/**
 * Debug only: checks the sign-scan pipeline (#19) on the phone without a corridor. It draws an
 * EH3 door plaque the way the back camera would see it from 1 to 6 m away (a 1080 px wide
 * portrait frame, the Pixel 7a's ~60° field of view, 4 cm characters), runs it through ML Kit's
 * on-device text recognizer and [SignReader], and prints what was read, the matched doors and
 * the distance read back. Results go to the screen and to logcat (tag `SignSelfTest`).
 *
 * `adb shell am start -n edu.wisc.maplens/.debug.SignSelfTestActivity`
 */
class SignSelfTestActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()

    // Debug-only report screen: plain English text is fine.
    @android.annotation.SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(32, 96, 32, 32)
            text = "Running…"
        }
        setContentView(ScrollView(this).apply { addView(out) })
        worker.execute {
            val report = runCatching { run() }.getOrElse { "Failed: $it" }
            Log.i(TAG, report)
            runOnUiThread { out.text = report }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun run(): String = buildString {
        val graph = MapAssets.loadGraph(this@SignSelfTestActivity)
        val config = TrackingConfig()
        val reader = SignReader(graph, config.signTextHeightMeters)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val width = 1080
        val height = 1440
        val hfov = Math.toRadians(HFOV_DEG)
        val focal = width / 2.0 / tan(hfov / 2)
        appendLine(
            "Sign scan self-test: ${width}x$height, hfov $HFOV_DEG°, f ${"%.0f".format(focal)} px"
        )
        appendLine("Character height ${config.signTextHeightMeters} m")
        appendLine()
        var passed = 0
        var total = 0
        for (label in listOf("3418", "3446", "3539A")) {
            for (meters in listOf(1.0, 2.0, 3.0, 4.0, 6.0)) {
                total++
                // How tall the characters are in the picture at this distance (pinhole).
                val textPx = focal * config.signTextHeightMeters / meters
                val frame = plaque(label, width, height, textPx.toFloat())
                val text = Tasks.await(recognizer.process(InputImage.fromBitmap(frame, 0)))
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    TextLine(
                        line.text,
                        box.left.toFloat(),
                        box.top.toFloat(),
                        box.right.toFloat(),
                        box.bottom.toFloat()
                    )
                }
                val read = reader.read(lines, width, hfov, 0.0).firstOrNull()
                // Far text gives no distance on purpose; then only the room has to be right.
                val near = read?.distanceMeters
                val ok = read != null && read.label == label && read.doors.isNotEmpty() &&
                    (near == null || near in meters * 0.6..meters * 1.4)
                if (ok) passed++
                appendLine(
                    String.format(
                        Locale.US,
                        "%-6s at %.0f m (%3.0f px text): %s",
                        label,
                        meters,
                        textPx,
                        if (read == null) {
                            "not read [${lines.joinToString { it.text }}]"
                        } else {
                            String.format(
                                Locale.US,
                                "read %s, doors %s, %s %s",
                                read.label,
                                read.doors,
                                near?.let { "~%.1f m".format(Locale.US, it) } ?: "far",
                                if (ok) "OK" else "OFF",
                            )
                        },
                    ),
                )
            }
        }
        recognizer.close()
        appendLine()
        appendLine(
            "$passed / $total read with the right room (and distance within ±40 % when given)"
        )
    }

    /** A grey corridor wall with a dark plaque and white room number, as on EH3 doors. */
    private fun plaque(label: String, width: Int, height: Int, textPx: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(196, 192, 184))
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        // Size the font so the digits' cap height is textPx.
        ink.textSize = 100f
        val bounds = android.graphics.Rect()
        ink.getTextBounds("3418", 0, 4, bounds)
        ink.textSize = 100f * textPx / bounds.height()
        val w = ink.measureText(label)
        val cx = width * 0.55f
        val cy = height * 0.45f
        val board = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 44, 52) }
        canvas.drawRoundRect(
            cx - w * 0.75f,
            cy - textPx * 1.6f,
            cx + w * 0.75f,
            cy + textPx * 1.6f,
            textPx * 0.3f,
            textPx * 0.3f,
            board,
        )
        canvas.drawText(label, cx - w / 2, cy + textPx / 2, ink)
        return bitmap
    }

    private companion object {
        const val TAG = "SignSelfTest"

        /** Pixel 7a main camera, portrait: about 60° across the upright picture's width. */
        const val HFOV_DEG = 60.0
    }
}
