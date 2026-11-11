package edu.wisc.maplens.nav

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import edu.wisc.maplens.R
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Find a room sign" (#19): a full-screen camera that reads room numbers on door signs and
 * shows each one in place, with how far away it is, like Live View's "point your phone at
 * buildings" step. Once one room is read steadily ([SignVoter]) it is handed to [onStable];
 * the screen then waits for [showResult] (accepted: the caller closes it; rejected: it says
 * why and keeps looking).
 *
 * Everything runs on the phone: CameraX frames, ML Kit's bundled text recognizer, and
 * [SignReader] for direction and distance. Nothing needs a network.
 */
@SuppressLint("ViewConstructor")
class SignScanView(
    context: Context,
    private val owner: LifecycleOwner,
    private val reader: SignReader,
    /** The camera's current map heading (the live heading), or null if not known yet. */
    private val heading: () -> Double?,
    private val onStable: (List<SignDetection>) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val preview = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val boxes = DetectionOverlay()
    private val statusTitle = TextView(context)
    private val statusBody = TextView(context)
    private val statusIcon = ProgressBar(context)
    private val voter = SignVoter()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val analysis = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var hfovRad = DEFAULT_HFOV
    private var lastFrameMs = 0L

    /** A room handed over and not answered yet: hold further reads until [showResult]. */
    private var waiting = false

    /** A rejected room and until when to ignore it, so the same sign isn't offered again. */
    private var ignored: Pair<String, Long>? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(boxes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(topCard(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            statusCard(),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        searching()
    }

    fun start() {
        val pending = ProcessCameraProvider.getInstance(context)
        pending.addListener(
            {
                val cameras = runCatching { pending.get() }.getOrNull() ?: return@addListener
                provider = cameras
                val ratio = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val shown = Preview.Builder().setResolutionSelector(ratio).build()
                shown.surfaceProvider = preview.surfaceProvider
                // Big enough frames to read a 4 cm room number from about 4 m away.
                val sharp = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1440, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()
                val frames = ImageAnalysis.Builder()
                    .setResolutionSelector(sharp)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                frames.setAnalyzer(analysis, ::analyze)
                cameras.unbindAll()
                val camera = runCatching {
                    val back = CameraSelector.DEFAULT_BACK_CAMERA
                    cameras.bindToLifecycle(owner, back, shown, frames)
                }.getOrNull() ?: return@addListener
                fieldOfView(camera.cameraInfo)?.let { hfovRad = it }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun stop() {
        provider?.unbindAll()
        provider = null
        analysis.shutdown()
        recognizer.close()
    }

    /**
     * The tracker's answer for the room last handed to [onStable]: [accepted] closes nothing
     * here (the caller removes the view); a rejection explains why and keeps scanning.
     */
    fun showResult(label: String, accepted: Boolean, tooFar: Boolean) {
        waiting = false
        voter.clear()
        if (accepted) {
            buzz(confirm = true)
            status(context.getString(R.string.sign_scan_found, label), null, busy = false)
            return
        }
        ignored = label to SystemClock.uptimeMillis() + IGNORE_MS
        buzz(confirm = false)
        status(
            context.getString(
                if (tooFar) R.string.sign_scan_too_far else R.string.sign_scan_unknown,
                label
            ),
            context.getString(R.string.sign_scan_try_another),
            busy = false,
        )
    }

    /** Distinct confirm and reject buzzes where the phone has them (Android 11+). */
    private fun buzz(confirm: Boolean) {
        val effect = when {
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ->
                HapticFeedbackConstants.LONG_PRESS
            confirm -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.REJECT
        }
        performHapticFeedback(effect)
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val image = proxy.image
        val now = SystemClock.uptimeMillis()
        if (image == null || waiting || now - lastFrameMs < FRAME_MS) {
            proxy.close()
            return
        }
        lastFrameMs = now
        val rotation = proxy.imageInfo.rotationDegrees
        val upright = rotation % 180 != 0
        val width = if (upright) proxy.height else proxy.width
        val height = if (upright) proxy.width else proxy.height
        recognizer.process(InputImage.fromMediaImage(image, rotation))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    TextLine(
                        line.text,
                        box.left.toFloat(),
                        box.top.toFloat(),
                        box.right.toFloat(),
                        box.bottom.toFloat(),
                        line.confidence,
                    )
                }
                onFrame(lines, width, height)
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Main thread: draw what this frame read, and hand over a room once it is steady. */
    private fun onFrame(lines: List<TextLine>, width: Int, height: Int) {
        if (waiting) return
        val facing = heading() ?: return
        val skip = ignored?.takeIf { SystemClock.uptimeMillis() < it.second }?.first
        val seen = reader.read(lines, width, hfovRad, facing).filter { it.label != skip }
        boxes.show(seen, width, height)
        voter.add(seen)
        val stable = voter.stable()
        when {
            stable != null -> {
                waiting = true
                status(
                    context.getString(R.string.sign_scan_checking, stable.first().label),
                    null,
                    busy = true,
                )
                onStable(stable)
            }
            seen.any { it.doors.isNotEmpty() } -> status(
                context.getString(
                    R.string.sign_scan_reading,
                    seen.first { it.doors.isNotEmpty() }.label
                ),
                context.getString(R.string.sign_scan_hold_steady),
                busy = true,
            )
            ignored == null || skip == null -> searching()
        }
    }

    private fun searching() = status(
        context.getString(R.string.sign_scan_looking),
        context.getString(R.string.sign_scan_looking_body),
        busy = true,
    )

    private fun status(title: String, body: String?, busy: Boolean) {
        statusTitle.text = title
        statusBody.text = body.orEmpty()
        statusBody.visibility = if (body == null) GONE else VISIBLE
        statusIcon.visibility = if (busy) VISIBLE else GONE
        contentDescription = listOfNotNull(title, body).joinToString(". ")
    }

    private fun topCard(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT),
        )
        addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(Color.WHITE)
                background = null
                contentDescription = context.getString(R.string.sign_scan_close)
                setOnClickListener { onClose() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        setText(R.string.sign_scan_title)
                        textSize = 20f
                        setTextColor(Color.WHITE)
                        typeface = MEDIUM
                        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                    },
                )
                addView(
                    TextView(context).apply {
                        setText(R.string.sign_scan_subtitle)
                        textSize = 14f
                        setTextColor(Color.argb(225, 255, 255, 255))
                    },
                )
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            },
        )
    }

    private fun statusCard(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(20).toFloat()
        }
        elevation = dp(6).toFloat()
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        statusTitle.apply {
            textSize = 16f
            setTextColor(Color.rgb(15, 23, 42))
            typeface = MEDIUM
        }
        statusBody.apply {
            textSize = 14f
            setTextColor(Color.rgb(71, 85, 105))
        }
        addView(statusIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(statusTitle)
                addView(statusBody)
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
            },
        )
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }.let { card ->
        FrameLayout(context).apply {
            setPadding(dp(12), 0, dp(12), dp(16))
            addView(card)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun fieldOfView(camera: CameraInfo): Double? {
        val info = Camera2CameraInfo.from(camera)
        val focal = info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
        )?.firstOrNull() ?: return null
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        // Portrait: the upright picture's width is the sensor's short side.
        return if (focal > 0) 2 * atan(minOf(sensor.width, sensor.height) / (2.0 * focal)) else null
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    /** Boxes and "3418 · ~2 m" tags drawn over the camera picture where the text is. */
    private inner class DetectionOverlay : View(context) {
        private var seen: List<SignDetection> = emptyList()
        private var sourceW = 1
        private var sourceH = 1
        private val matched = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = MATCH_GREEN
        }
        private val unknown = Paint(matched).apply {
            color = Color.WHITE
            pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
        }
        private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15 * density
            typeface = MEDIUM
        }
        private val rect = RectF()

        fun show(detections: List<SignDetection>, width: Int, height: Int) {
            seen = detections
            sourceW = width
            sourceH = height
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            // The preview fills and centre-crops; map picture pixels the same way.
            val scale = max(width / sourceW.toFloat(), height / sourceH.toFloat())
            val dx = (width - sourceW * scale) / 2
            val dy = (height - sourceH * scale) / 2
            for (d in seen) {
                val known = d.doors.isNotEmpty()
                val pad = 6 * density
                rect.set(
                    d.line.left * scale + dx - pad,
                    d.line.top * scale + dy - pad,
                    d.line.right * scale + dx + pad,
                    d.line.bottom * scale + dy + pad,
                )
                canvas.drawRoundRect(
                    rect,
                    8 * density,
                    8 * density,
                    if (known) matched else unknown
                )
                val tag = if (known) {
                    val m = d.distanceMeters
                    if (m != null) {
                        context.getString(R.string.sign_scan_tag, d.label, m)
                    } else {
                        d.label
                    }
                } else {
                    context.getString(R.string.sign_scan_tag_unknown, d.label)
                }
                val tw = tagText.measureText(tag)
                val th = 28 * density
                val left = rect.centerX() - tw / 2 - 10 * density
                val top = rect.top - th - 6 * density
                tagFill.color = if (known) MATCH_GREEN else Color.argb(200, 32, 33, 36)
                canvas.drawRoundRect(
                    left,
                    top,
                    left + tw + 20 * density,
                    top + th,
                    th / 2,
                    th / 2,
                    tagFill
                )
                canvas.drawText(tag, left + 10 * density, top + th * 0.68f, tagText)
            }
        }
    }

    private companion object {
        /** Read about four frames a second: enough to vote, light on the battery. */
        const val FRAME_MS = 250L

        /** A rejected room is skipped this long so the walker can turn to another sign. */
        const val IGNORE_MS = 4_000L

        /** A typical phone main camera's portrait field of view, until the lens reports its own. */
        const val DEFAULT_HFOV = 1.05

        val MATCH_GREEN = Color.rgb(24, 128, 56)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
