package edu.wisc.maplens

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import edu.wisc.maplens.mapparse.MapParser
import edu.wisc.maplens.mapparse.OfflineMapParser
import edu.wisc.maplens.mapparse.ParseProgress
import edu.wisc.maplens.mapparse.ParseResult
import edu.wisc.maplens.mapparse.ParseStage
import edu.wisc.maplens.mapparse.PosterAim
import edu.wisc.maplens.mapparse.PosterRetakeException
import edu.wisc.maplens.mapparse.PosterWarmup
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.routing.AStarRouter
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Owns one capture/review session, independently of the bundled navigation demo. */
class PosterCaptureActivity : ComponentActivity() {
    private lateinit var cameraView: PreviewView
    private lateinit var cameraFrame: FrameLayout
    private lateinit var aimOverlay: AimOverlay
    private lateinit var photoView: ImageView
    private lateinit var progress: PosterProgressView
    private lateinit var map: MapView
    private lateinit var roomPanel: PosterReviewPanel
    private lateinit var mapBar: LinearLayout
    private lateinit var mapButton: Button
    private lateinit var status: TextView
    private lateinit var retakeCard: LinearLayout
    private lateinit var retakeIcon: ImageView
    private lateinit var retakeTitle: TextView
    private lateinit var retakeBody: TextView
    private lateinit var action: Button
    private lateinit var details: Button
    private lateinit var cancel: Button
    private lateinit var save: Button
    private lateinit var importButton: Button
    private lateinit var fastSwitch: Switch

    /**
     * Fast mode: the ML Kit parser (about 5 s). Off: the original parser (classic package),
     * kept side by side for comparison. Remembered between scans.
     */
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    /** Developer mode: Edit map, Details and the parser switch. User mode: scan and save. */
    private val developer by lazy { AppMode.isDeveloper(this) }
    private var fastMode: Boolean
        // User mode always reads with the Fast parser; the switch is a developer tool.
        get() = !developer || prefs.getBoolean(PREF_FAST, true)
        set(value) = prefs.edit().putBoolean(PREF_FAST, value).apply()
    private var camera: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null

    /** Preview frames are looked at here, one at a time, for the framing hints. */
    private val aimWorker = Executors.newSingleThreadExecutor()
    private val aimGuide = AimGuide()
    private var lastLookMs = 0L

    /** Lens focal length over the sensor's long side (focal pixels per pixel of long side). */
    @Volatile private var focalShare = DEFAULT_FOCAL_SHARE

    /** Latest turn rate of the phone (rad/s): a turning phone blurs the photo. */
    @Volatile private var turnRate = 0.0
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val gyro = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            turnRate = sqrt((x * x + y * y + z * z).toDouble())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var session = NavigationSession(AStarRouter())
    private var review: ParseResult? = null
    private var busy = false
    private val worker = Executors.newSingleThreadExecutor()
    private val cameraCallbacks by lazy { ContextCompat.getMainExecutor(this) }
    private val workLock = Any()
    private var generation = 0
    private var parsingThread: Thread? = null
    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startCamera() else showPermission() }

    /** A poster photo taken earlier: from the photo library, Files or Drive. */
    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(::importPhoto) }

    /** Back clears the mark, then returns to the full map, and only then leaves the screen. */
    private val reviewBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (map.selectedRoom != null) {
                map.selectedRoom = null
                roomPanel.select(null)
                updateMapButton()
            } else {
                map.showAll()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, reviewBack)
        cameraView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        aimOverlay = AimOverlay(this)
        cameraFrame = FrameLayout(this).apply {
            addView(cameraView, FrameLayout.LayoutParams(-1, -1))
            addView(aimOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        photoView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(236, 238, 240))
            contentDescription = getString(R.string.poster_photo_description)
        }
        progress = PosterProgressView(this, developer).apply { visibility = View.GONE }
        map = MapView(this).apply {
            visibility = View.GONE
            contentDescription = getString(R.string.poster_map_description)
            setBackgroundColor(Color.rgb(236, 238, 240))
        }
        roomPanel = PosterReviewPanel(this).apply {
            visibility = View.GONE
            onPick = { room ->
                map.selectedRoom = room
                // A zoomed map follows the list. The whole map stays whole until asked.
                if (room != null && map.isZoomed) map.focus(room.preview.bounds)
                updateMapButton()
            }
        }
        map.onMapTap = { tap ->
            // Tapping beside a room, or the marked room again, leaves a clean map.
            val room = map.roomNear(tap).takeIf { it != map.selectedRoom }
            map.selectedRoom = room
            roomPanel.select(room)
            updateMapButton()
        }
        map.onViewChange = { updateMapButton() }
        mapButton = styledButton(ButtonStyle.TONAL).apply {
            isSingleLine = true
            textSize = Ui.CAPTION
            visibility = View.GONE
            setOnClickListener {
                val room = map.selectedRoom
                if (map.isZoomed || room == null) {
                    map.showAll()
                } else {
                    map.focus(room.preview.bounds)
                }
            }
        }
        mapBar = LinearLayout(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = mapKey()
                    textSize = 13f
                    setTextColor(POSTER_MUTED)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(mapButton)
        }
        val pad = (12 * resources.displayMetrics.density).roundToInt()
        status = TextView(this).apply {
            textSize = Ui.BODY
            setTextColor(Ui.INK)
            setLineSpacing(0f, 1.15f)
            setPadding(0, pad / 2, 0, pad)
        }
        // Why the last scan was sent back, in Google's amber warning style, so it is never
        // mistaken for the usual "hold the phone" line above the camera.
        retakeIcon = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER }
        retakeTitle = TextView(this).apply {
            textSize = Ui.BODY
            setTextColor(RETAKE_INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        retakeBody = TextView(this).apply {
            textSize = Ui.CAPTION
            setTextColor(RETAKE_INK)
            setLineSpacing(0f, 1.15f)
        }
        retakeCard = LinearLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(16), dp(12))
            background = rounded(RETAKE_FILL, radiusDp = 16, stroke = RETAKE_EDGE)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            addView(
                FrameLayout(context).apply {
                    background = rounded(RETAKE_EDGE, radiusDp = 18)
                    addView(retakeIcon, FrameLayout.LayoutParams(-1, -1))
                },
                LinearLayout.LayoutParams(dp(36), dp(36)),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(retakeTitle)
                    addView(
                        retakeBody,
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) },
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) },
            )
        }
        // No icon: the label changes with the step (allow camera, capture, retake).
        action = styledButton(ButtonStyle.PRIMARY, getString(R.string.capture_poster))
        details = styledButton(ButtonStyle.SECONDARY).apply { visibility = View.GONE }
        // Edit map is the main way on. This one is for a scan that needs no correcting.
        save = styledButton(ButtonStyle.TONAL, getString(R.string.map_save)).apply {
            visibility = View.GONE
        }
        cancel = styledButton(ButtonStyle.SECONDARY, getString(android.R.string.cancel)).apply {
            visibility = View.GONE
            setOnClickListener { resetCapture() }
        }
        // The second way in, spelled out on its own full-width row just above capture, so
        // it reads as "or use a photo you already have" and capture keeps a single line.
        importButton = styledButton(
            ButtonStyle.SECONDARY,
            getString(R.string.poster_import),
            R.drawable.ic_photo_library,
        ).apply {
            contentDescription = getString(R.string.poster_import_description)
            setOnClickListener { if (!busy) pickPhoto.launch("image/*") }
        }
        val buttons = LinearLayout(this).apply {
            setPadding(0, pad, 0, pad)
            // An 8 dp gap between visible buttons only; hidden ones get no divider.
            dividerDrawable = GradientDrawable().apply { setSize(dp(8), 0) }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            for (button in listOf(details, cancel, action)) {
                addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val back = ImageButton(this).apply {
            setImageDrawable(icon(R.drawable.ic_arrow_back, Ui.INK))
            background = pressable(rounded(Color.TRANSPARENT, radiusDp = 24))
            contentDescription = getString(R.string.nav_back)
            // Through the dispatcher, so Back still clears a marked room or the zoom first.
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        fastSwitch = Switch(this).apply {
            setText(R.string.poster_fast)
            textSize = Ui.CAPTION
            setTextColor(Ui.INK_SECONDARY)
            switchPadding = dp(8)
            isChecked = fastMode
            contentDescription = getString(R.string.poster_fast_description)
            setOnCheckedChangeListener { _, checked ->
                fastMode = checked
                if (checked && review == null) PosterWarmup.start() else PosterWarmup.release()
                Toast.makeText(
                    context,
                    if (checked) R.string.poster_fast_on else R.string.poster_fast_off,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(Ui.TOUCH_DP), dp(Ui.TOUCH_DP)))
            addView(
                TextView(context).apply {
                    setText(R.string.home_scan)
                    textSize = Ui.BODY
                    setTextColor(Ui.INK_SECONDARY)
                    markHeading()
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) },
            )
            if (developer) addView(fastSwitch, LinearLayout.LayoutParams(-2, dp(Ui.TOUCH_DP)))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BACKGROUND)
            // Window insets only reach here when Android draws edge to edge (15+), so the
            // margins are set up front and the listener only adds the bars on top.
            setPadding(pad, pad, pad, 0)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(
                    pad + insets.systemWindowInsetLeft,
                    pad + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
                insets
            }
            addView(topBar, LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(12) })
            addView(status)
            addView(retakeCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
            addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(photoView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(mapBar)
            addView(roomPanel, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(progress)
            addView(save, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
            addView(importButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
            addView(buttons)
        }
        setContentView(root)
        if (hasCameraPermission()) startCamera() else showPermission()
    }

    override fun onResume() {
        super.onResume()
        sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors?.registerListener(gyro, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensors?.unregisterListener(gyro)
        turnRate = 0.0
        if (busy) resetCapture()
    }

    override fun onDestroy() {
        invalidateCapture()
        stopCamera()
        PosterWarmup.release()
        worker.shutdown()
        aimWorker.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermission() {
        status.setText(R.string.poster_permission)
        importButton.visibility = View.VISIBLE
        action.setText(R.string.poster_grant_permission)
        action.isEnabled = true
        action.setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera(message: CharSequence? = null) {
        status.text = message ?: getString(R.string.poster_opening)
        action.visibility = View.VISIBLE
        importButton.visibility = View.VISIBLE
        action.isEnabled = false
        cancel.visibility = View.GONE
        progress.visibility = View.GONE
        photoView.visibility = View.GONE
        clearPhoto()
        cameraFrame.visibility = View.VISIBLE
        // Load the Fast parser's models while the poster is being framed.
        if (fastMode) PosterWarmup.start()
        val pending = ProcessCameraProvider.getInstance(this)
        pending.addListener({
            if (isDestroyed || isFinishing || review != null) return@addListener
            try {
                val provider = pending.get()
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(3200, 2400),
                            // Room suffixes need the extra pixels. upright() bounds the result.
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .setResolutionFilter { sizes, _ ->
                        sizes.filter { maxOf(it.width, it.height) <= MAX_CAPTURE_SIDE }
                    }
                    .build()
                val view = Preview.Builder().build()
                view.setSurfaceProvider(cameraView.surfaceProvider)
                val photo = ImageCapture.Builder()
                    .setResolutionSelector(resolution)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val look = aimAnalysis()
                val bound = try {
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        view,
                        photo,
                        look,
                    )
                } catch (failure: IllegalArgumentException) {
                    // A camera that can't stream a third output still takes the photo.
                    Log.w(TAG, "No framing hints on this camera", failure)
                    provider.unbindAll()
                    aimOverlay.visibility = View.GONE
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, view, photo)
                }
                focalShare = focalShare(bound)
                camera = provider
                preview = view
                capture = photo
                analysis = look.takeIf { aimOverlay.visibility == View.VISIBLE }
                ready(message ?: getString(R.string.poster_ready))
            } catch (failure: Exception) {
                Log.w(TAG, "Camera bind failed", failure)
                status.setText(R.string.poster_camera_failed)
            }
        }, cameraCallbacks)
    }

    private fun stopCamera() {
        preview?.let { camera?.unbind(it) }
        capture?.let { camera?.unbind(it) }
        analysis?.let {
            it.clearAnalyzer()
            camera?.unbind(it)
        }
        preview = null
        capture = null
        analysis = null
        aimOverlay.clear()
    }

    /** Small, latest-only preview frames for the framing hints. */
    private fun aimAnalysis(): ImageAnalysis {
        val size = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(AIM_FRAME_WIDTH, AIM_FRAME_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                )
            )
            .build()
        return ImageAnalysis.Builder()
            .setResolutionSelector(size)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .apply { setAnalyzer(aimWorker, ::lookForPoster) }
    }

    /** On [aimWorker]: finds the map in one preview frame and posts the hint. */
    private fun lookForPoster(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLookMs < AIM_INTERVAL_MS) {
            image.close()
            return
        }
        lastLookMs = now
        val sighting = image.use {
            val frame = it.toBitmap()
            try {
                val focal = focalShare * maxOf(frame.width, frame.height)
                runCatching { PosterAim.look(frame, it.imageInfo.rotationDegrees, focal) }
                    .onFailure { failure -> Log.w(TAG, "Framing look failed", failure) }
                    .getOrNull()
            } finally {
                frame.recycle()
            }
        }
        runOnUiThread {
            if (analysis == null || busy || isDestroyed) return@runOnUiThread
            val state = aimGuide.update(sighting, now, turnRate)
            aimOverlay.show(state)
            // User mode won't take a photo the parse would get wrong: the chip says how to fix
            // it, and the shutter comes back as soon as the phone is close enough.
            if (!developer && review == null) {
                action.isEnabled = capture != null && !state.blocksCapture
            }
        }
    }

    /**
     * The lens's focal length over the sensor's long side, so a frame's focal length in pixels
     * is this times its long side. Assumes the 4:3 stream spans the sensor's width.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun focalShare(camera: Camera): Double = runCatching {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val focal = checkNotNull(
            info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ).first()
        val sensor = checkNotNull(
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        )
        (focal / maxOf(sensor.width, sensor.height)).toDouble()
    }.getOrDefault(DEFAULT_FOCAL_SHARE)

    private fun ready(message: CharSequence = getString(R.string.poster_ready)) {
        busy = false
        fastSwitch.isEnabled = true
        status.text = message
        cancel.visibility = View.GONE
        action.setText(R.string.capture_poster)
        action.isEnabled = capture != null
        action.setOnClickListener { takePhoto() }
    }

    private fun takePhoto() {
        val photo = capture ?: return
        if (busy) return
        val id = startParsing()
        cameraView.display?.let { photo.targetRotation = it.rotation }
        photo.takePicture(
            cameraCallbacks,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (isDestroyed || id != generation) {
                        image.close()
                        return
                    }
                    // The live preview would compete with the parser for CPU.
                    stopCamera()
                    val share = focalShare
                    worker.execute {
                        parse(id, { maxOf(it.width, it.height) * share }) {
                            image.use(::upright)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (id == generation && !isDestroyed) {
                        Log.w(TAG, "Capture failed", exception)
                        progress.visibility = View.GONE
                        action.visibility = View.VISIBLE
                        importButton.visibility = View.VISIBLE
                        ready(getString(R.string.poster_capture_failed))
                    }
                }
            }
        )
    }

    /** Shows the parsing state and returns this attempt's generation. */
    private fun startParsing(): Int {
        hideRetake()
        val id = synchronized(workLock) { ++generation }
        busy = true
        fastSwitch.isEnabled = false
        status.text = SpannableStringBuilder(getString(R.string.poster_parsing_title)).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.25f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        progress.show(ParseProgress(ParseStage.STRAIGHTEN))
        progress.visibility = View.VISIBLE
        cancel.setText(android.R.string.cancel)
        cancel.visibility = View.VISIBLE
        action.visibility = View.GONE
        importButton.visibility = View.GONE
        return id
    }

    /** Parses a saved photo of a poster exactly like a fresh capture. */
    private fun importPhoto(uri: Uri) {
        if (busy || review != null) return
        val id = startParsing()
        // The live preview would compete with the parser for CPU.
        stopCamera()
        worker.execute {
            val focal35 = runCatching {
                contentResolver.openInputStream(uri)?.use {
                    android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                        0,
                    )
                }
            }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_FOCAL_35MM
            // A 35 mm-equivalent focal length is over the 43.27 mm diagonal of 35 mm film.
            parse(id, { hypot(it.width.toDouble(), it.height.toDouble()) * focal35 / 43.27 }) {
                decodePhoto(uri)
            }
        }
    }

    /**
     * Decodes [uri] upright (EXIF rotation applied) and no larger than a capture
     * ([MAX_PHOTO_SIDE]), in a software bitmap the parser can read.
     */
    private fun decodePhoto(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val side = maxOf(info.size.width, info.size.height)
                if (side > MAX_PHOTO_SIDE) {
                    val scale = MAX_PHOTO_SIDE.toFloat() / side
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt(),
                        (info.size.height * scale).roundToInt(),
                    )
                }
            }
        }
        // Android 8 has no ImageDecoder: the photo is decoded as stored (no EXIF turn).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw PosterRetakeException(getString(R.string.poster_import_unreadable))
        val scale = minOf(1f, MAX_PHOTO_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (scale == 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
            true,
        ).also { if (it !== bitmap) bitmap.recycle() }
    }

    /**
     * Parses the photo [load] gives, on the worker, unless a newer attempt replaced [id].
     * [focalPx] is the lens's focal length in that photo's pixels, for the angle check.
     */
    private fun parse(id: Int, focalPx: (Bitmap) -> Double, load: () -> Bitmap) {
        synchronized(workLock) {
            if (id != generation) return
            parsingThread = Thread.currentThread()
        }
        val result = try {
            val photo = load()
            checkAngle(photo, focalPx(photo))
            val shown = Bitmap.createScaledBitmap(
                photo,
                photo.width * PHOTO_VIEW_SIDE / maxOf(photo.width, photo.height),
                photo.height * PHOTO_VIEW_SIDE / maxOf(photo.width, photo.height),
                true,
            )
            runOnUiThread {
                if (id == generation && !isDestroyed) {
                    photoView.setImageBitmap(shown)
                    // The photo comes clear as it is read, in step with the real progress.
                    val reveal = PhotoReveal(::photoArea)
                    photoView.foreground = reveal
                    progress.onFraction = reveal::reveal
                    photoView.visibility = View.VISIBLE
                    cameraFrame.visibility = View.GONE
                }
            }
            val began = android.os.SystemClock.elapsedRealtime()
            val onProgress: (ParseProgress) -> Unit = { update ->
                runOnUiThread {
                    if (id == generation && !isDestroyed) progress.show(update)
                }
            }
            val fast = fastMode
            val parser: MapParser = if (fast) {
                OfflineMapParser(onProgress)
            } else {
                edu.wisc.maplens.mapparse.classic.OfflineMapParser(onProgress)
            }
            try {
                Result.success(parser.parse(photo)).also {
                    val ms = android.os.SystemClock.elapsedRealtime() - began
                    Log.i(TAG, "parsed (${if (fast) "fast" else "normal"}) in $ms ms")
                }
            } finally {
                photo.recycle()
            }
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            synchronized(workLock) {
                parsingThread = null
                Thread.interrupted()
            }
        }
        runOnUiThread {
            if (id != generation || isDestroyed || isFinishing) {
                result.getOrNull()?.rectified?.recycle()
                return@runOnUiThread
            }
            // Read: the photo comes fully clear before the map takes its place.
            result.onSuccess { (photoView.foreground as? PhotoReveal)?.reveal(1f) }
            result.onSuccess { if (developer) showReview(it) else saveForUser(it) }.onFailure {
                Log.w(TAG, "Poster processing failed", it)
                busy = false
                fastSwitch.isEnabled = true
                startCamera()
                when (it) {
                    is TooAngled -> showRetake(R.string.poster_retake_angled, it.message, it.iconId)
                    is PosterRetakeException -> showRetake(R.string.poster_retake_title, it.message)
                    else -> showRetake(
                        R.string.poster_retake_title,
                        getString(R.string.poster_parse_failed),
                    )
                }
            }
        }
    }

    /**
     * The map in [photo] seen too far from straight on: user mode stops here with how to
     * retake it (a slanted map loses rooms and doors); developer mode goes on, to test with.
     */
    private fun checkAngle(photo: Bitmap, focalPx: Double) {
        val seen = runCatching { PosterAim.look(photo, 0, focalPx) }.getOrNull() ?: return
        if (seen.corners == null) return
        val angle = maxOf(abs(seen.yawDegrees), abs(seen.pitchDegrees))
        Log.i(TAG, "photo angle yaw %.1f pitch %.1f".format(seen.yawDegrees, seen.pitchDegrees))
        if (angle <= AimParams().rejectDegrees) return
        if (developer) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.poster_angled_developer, angle.roundToInt()),
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        photo.recycle()
        val (move, iconId) = when (moveFor(seen.yawDegrees, seen.pitchDegrees)) {
            AimHint.STEP_RIGHT -> R.string.aim_step_right to R.drawable.ic_aim_right
            AimHint.STEP_LEFT -> R.string.aim_step_left to R.drawable.ic_aim_left
            AimHint.LOWER -> R.string.aim_lower to R.drawable.ic_aim_down
            else -> R.string.aim_raise to R.drawable.ic_aim_up
        }
        throw TooAngled(
            getString(R.string.poster_too_angled, angle.roundToInt(), getString(move)),
            iconId,
        )
    }

    /** A photo sent back for its angle, with the arrow for the way to move. */
    private class TooAngled(message: String, val iconId: Int) : Exception(message)

    /**
     * Shows why the last scan was sent back: a short title, the reason and what to do, with a
     * light buzz and a spoken announcement. Stays until the next scan starts.
     */
    private fun showRetake(titleId: Int, reason: String?, iconId: Int = R.drawable.ic_warning) {
        retakeTitle.setText(titleId)
        retakeBody.text = reason ?: getString(R.string.poster_parse_failed)
        retakeIcon.setImageDrawable(icon(iconId, Color.WHITE, 20))
        retakeCard.visibility = View.VISIBLE
        retakeCard.alpha = 0f
        retakeCard.translationY = -dpf(8f)
        retakeCard.animate().alpha(1f).translationY(0f).setDuration(220).start()
        retakeCard.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.HapticFeedbackConstants.REJECT
            } else {
                android.view.HapticFeedbackConstants.LONG_PRESS
            },
        )
        status.setText(R.string.poster_ready)
    }

    /** Where the photo is drawn in [photoView]: fitted inside it, so not all of the view. */
    private fun photoArea(): android.graphics.Rect? {
        val image = photoView.drawable ?: return null
        val drawn = android.graphics.RectF(
            0f,
            0f,
            image.intrinsicWidth.toFloat(),
            image.intrinsicHeight.toFloat(),
        )
        photoView.imageMatrix.mapRect(drawn)
        drawn.offset(photoView.paddingLeft.toFloat(), photoView.paddingTop.toFloat())
        return android.graphics.Rect().also { drawn.roundOut(it) }
    }

    /** Takes the photo (and its reading sweep) off the screen. */
    private fun clearPhoto() {
        (photoView.foreground as? PhotoReveal)?.stop()
        photoView.foreground = null
        progress.onFraction = null
        photoView.setImageDrawable(null)
    }

    private fun hideRetake() {
        retakeCard.visibility = View.GONE
    }

    private fun upright(image: ImageProxy): Bitmap {
        require(maxOf(image.width, image.height) <= MAX_CAPTURE_SIDE)
        val bitmap = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val scale = minOf(1f, MAX_PHOTO_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(scale, scale)
        }
        if (matrix.isIdentity) return bitmap
        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } finally {
            bitmap.recycle()
        }
    }

    private fun invalidateCapture() {
        synchronized(workLock) {
            generation++
            parsingThread?.interrupt()
        }
    }

    private fun resetCapture() {
        invalidateCapture()
        busy = false
        fastSwitch.isEnabled = true
        if (review != null) {
            map.bitmap = null
            map.review = null
            map.reviewRooms = emptyList()
            map.selectedRoom = null
            reviewBack.isEnabled = false
            save.visibility = View.GONE
            roomPanel.visibility = View.GONE
            map.start = null
            review = null
            session = NavigationSession(AStarRouter())
            map.visibility = View.GONE
            mapBar.visibility = View.GONE
            details.visibility = View.GONE
            startCamera()
        } else if (capture == null) {
            startCamera()
        } else {
            progress.visibility = View.GONE
            action.visibility = View.VISIBLE
            ready()
        }
    }

    /**
     * User mode: no checking screen. A map that navigation accepts is saved and opened at
     * once; one it can't use (no START, crossing corridors) sends the user back to retake.
     */
    private fun saveForUser(result: ParseResult) {
        val verified = verifiedOrNull(result.graph)
        if (verified == null) {
            result.rectified.recycle()
            busy = false
            startCamera()
            showRetake(R.string.poster_retake_title, getString(R.string.poster_user_blocked))
            return
        }
        status.setText(R.string.poster_user_saving)
        cancel.visibility = View.GONE
        saveAndNavigate(verified, result.rectified, labelsOf(result))
    }

    private fun showReview(result: ParseResult) {
        session.onMapCaptured(result.graph)
        val graph = checkNotNull(session.graph)
        review = result
        busy = false
        fastSwitch.isEnabled = true
        PosterWarmup.release()
        stopCamera()
        cameraFrame.visibility = View.GONE
        photoView.visibility = View.GONE
        clearPhoto()
        progress.visibility = View.GONE
        cancel.visibility = View.GONE
        importButton.visibility = View.GONE
        action.visibility = View.VISIBLE
        val start = graph.nodes[graph.startNodeId]?.takeIf { it.type == NodeType.START }
        val rooms = reviewRooms(result)
        // The map takes only the height it fills. The room list uses the rest of the screen.
        val screen = resources.displayMetrics
        val parent = map.parent as View
        val mapHeight = (parent.width - parent.paddingLeft - parent.paddingRight) *
            result.rectified.height / result.rectified.width
        map.layoutParams = LinearLayout.LayoutParams(
            -1,
            mapHeight.coerceAtMost(screen.heightPixels * 45 / 100),
        )
        map.review = graph
        map.bitmap = result.rectified
        map.reviewRooms = rooms
        map.start = start?.position
        map.visibility = View.VISIBLE
        mapBar.visibility = View.VISIBLE
        roomPanel.show(rooms, result.rectified)
        map.selectedRoom = null
        roomPanel.visibility = View.VISIBLE
        updateMapButton()
        val issues = result.warnings + reviewProblems(graph)
        status.text = reviewSummary(rooms.count { it.check != RoomCheck.CLEAR }, start != null)
        details.visibility = if (developer) View.VISIBLE else View.GONE
        details.text = getString(R.string.poster_review_details, issues.size)
        details.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.poster_review)
                .setMessage(issues.joinToString("\n\n") { "• $it" })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        cancel.setText(R.string.poster_retake)
        cancel.visibility = View.VISIBLE
        // Saving needs a graph NavigationSession accepts: a real START and validate() clean.
        val verified = verifiedOrNull(result.graph)
        val toCheck = rooms.count { it.check != RoomCheck.CLEAR }
        action.isEnabled = true
        action.setText(R.string.poster_edit)
        action.setOnClickListener { openEditor(result, graph) }
        save.visibility = View.VISIBLE
        save.isEnabled = verified != null
        save.text = getString(
            if (verified != null) R.string.map_save else R.string.map_save_blocked,
        )
        save.setOnClickListener { verified?.let { confirmSave(it, result.rectified, toCheck) } }
    }

    /** The editor works on its own copy on disk. This screen's job is done once it opens. */
    private fun openEditor(result: ParseResult, graph: FloorGraph) {
        action.isEnabled = false
        save.isEnabled = false
        val rooms = result.rooms.map {
            RoomDraft(
                it.label,
                it.bounds.left,
                it.bounds.top,
                it.bounds.right,
                it.bounds.bottom,
                it.entranceNodeId,
                it.confidence,
                it.possible,
            )
        }
        worker.execute {
            val written = runCatching {
                DraftStore.of(this).start(MapDraft(graph, rooms)) { file ->
                    file.outputStream().use {
                        check(result.rectified.compress(Bitmap.CompressFormat.JPEG, 95, it))
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                written.onSuccess {
                    startActivity(Intent(this, GraphEditorActivity::class.java))
                    finish()
                }.onFailure {
                    Log.w(TAG, "Draft not written", it)
                    action.isEnabled = true
                    save.isEnabled = verifiedOrNull(graph) != null
                    status.setText(R.string.poster_draft_failed)
                }
            }
        }
    }

    /** The graph as a fresh session would verify it, or null when it would refuse it. */
    private fun verifiedOrNull(draft: FloorGraph): FloorGraph? = runCatching {
        NavigationSession(AStarRouter()).run {
            onMapCaptured(draft)
            onGraphVerified(draft)
            graph
        }
    }.getOrNull()

    private fun confirmSave(graph: FloorGraph, image: Bitmap, toCheck: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.map_save_title)
            .setMessage(getString(R.string.map_save_message, toCheck))
            .setPositiveButton(R.string.map_save) { _, _ ->
                saveAndNavigate(graph, image, review?.let(::labelsOf).orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Writes the map off the UI thread, then opens it for navigation. */
    /** Where the parser read each room number: kept with the map so taps on them work. */
    private fun labelsOf(result: ParseResult) = result.rooms.map {
        SavedLabel(it.label, it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)
    }

    private fun saveAndNavigate(graph: FloorGraph, image: Bitmap, labels: List<SavedLabel>) {
        save.isEnabled = false
        action.isEnabled = false
        val name = suggestedMapName(graph)
        val store = MapAssets.savedMaps(this)
        worker.execute {
            val saved = runCatching {
                store.save(graph, name, labels = labels) { file ->
                    file.outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "JPEG failed" }
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                saved.onSuccess {
                    startActivity(IndoorNavActivity.open(this, it.id))
                    finish()
                }.onFailure {
                    Log.w(TAG, "Saving the map failed", it)
                    save.isEnabled = true
                    action.isEnabled = true
                    if (!developer) {
                        busy = false
                        startCamera()
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.map_save_failed, it.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** Always one tap back to the whole map, and never an unexpected zoom. */
    private fun updateMapButton() {
        val room = map.selectedRoom
        when {
            map.isZoomed -> mapButton.setText(R.string.poster_full_map)
            room != null -> mapButton.text = getString(R.string.poster_zoom_to, room.preview.label)
            else -> {
                // Invisible, not gone, so the list below does not jump as rooms are picked.
                mapButton.visibility = View.INVISIBLE
                reviewBack.isEnabled = false
                return
            }
        }
        mapButton.visibility = View.VISIBLE
        reviewBack.isEnabled = true
    }

    private fun mapKey(): CharSequence = SpannableStringBuilder().apply {
        for ((color, label) in listOf(
            REVIEW_CHECK to R.string.poster_legend_check,
            REVIEW_ROOM to R.string.poster_legend_room,
        )) {
            val from = length
            append("● ")
            setSpan(ForegroundColorSpan(color), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(getString(label) + "  ")
        }
    }

    private fun reviewSummary(toCheck: Int, hasStart: Boolean): CharSequence {
        val text = SpannableStringBuilder()
        fun add(value: String, vararg spans: Any) {
            val from = text.length
            text.append(value)
            spans.forEach { text.setSpan(it, from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }

        add(getString(R.string.poster_review), StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.25f))
        add(
            "\n" + resources.getQuantityString(R.plurals.poster_need_look, toCheck, toCheck) + "\n",
            StyleSpan(Typeface.BOLD),
            ForegroundColorSpan(if (toCheck > 0) REVIEW_CHECK else REVIEW_ROOM),
        )
        val startText =
            if (hasStart) R.string.poster_start_detected else R.string.poster_start_missing
        add(getString(startText), ForegroundColorSpan(if (hasStart) REVIEW_START else REVIEW_CHECK))
        return text
    }

    private companion object {
        const val MAX_CAPTURE_SIDE = 4096
        const val PREFS = "poster_scan"
        const val PREF_FAST = "fast"

        /** Longest side handed to the parser, for captures and imported photos alike. */
        const val MAX_PHOTO_SIDE = 3200
        const val PHOTO_VIEW_SIDE = 1280
        const val TAG = "PosterCapture"

        /** Framing-hint frames: the size the detector was checked at (Nov 14, 16 photos). */
        const val AIM_FRAME_WIDTH = 1280
        const val AIM_FRAME_HEIGHT = 960

        /** About 8 looks a second: steady hints, and the CPU stays free for the warm-up. */
        const val AIM_INTERVAL_MS = 120L

        /** Focal length over sensor long side when the camera won't say: a 26 mm phone lens. */
        const val DEFAULT_FOCAL_SHARE = 0.75

        /** A phone's main camera, in 35 mm terms, when an imported photo doesn't say. */
        const val DEFAULT_FOCAL_35MM = 26

        /** Google's amber warning colours: yellow 50 fill, yellow 700 edge, brown ink. */
        val RETAKE_FILL = Color.rgb(254, 247, 224)
        val RETAKE_EDGE = Color.rgb(242, 153, 0)
        val RETAKE_INK = Color.rgb(94, 61, 0)
    }
}
