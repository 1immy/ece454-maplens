package edu.wisc.maplens.debug

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import edu.wisc.maplens.R
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.tracking.CameraPose
import edu.wisc.maplens.tracking.OrientObservation
import edu.wisc.maplens.tracking.OrientResult
import edu.wisc.maplens.tracking.OrientationSolver
import edu.wisc.maplens.tracking.ScanFrame
import edu.wisc.maplens.tracking.TextHit
import edu.wisc.maplens.tracking.TrackingConfig
import edu.wisc.maplens.tracking.VanishingPoint
import edu.wisc.maplens.tracking.VanishingPointFinder
import edu.wisc.maplens.tracking.matchRoomLabel
import edu.wisc.maplens.tracking.scanObservations
import edu.wisc.maplens.tracking.wrapHeading
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only scan to orient (ADR 0004): a guided turn on the spot takes evenly spaced photos,
 * each read on the phone (room numbers by ML Kit's bundled model, corridor lines by
 * [VanishingPointFinder]); [OrientationSolver] then matches them to the traced map. No network.
 * Every scan is saved with its photos so it can be replayed while tuning.
 */
class OrientScanActivity : ComponentActivity(), SensorEventListener {
    private val config = TrackingConfig()
    private val solver = OrientationSolver(config)
    private val finder = VanishingPointFinder(config)
    private val analysis = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val recognizer by lazy {
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }
    private lateinit var sensors: SensorManager
    private lateinit var guide: ScanGuideView
    private lateinit var prompt: TextView
    private lateinit var result: TextView
    private lateinit var startButton: Button
    private lateinit var nodeField: EditText
    private var graph: FloorGraph? = null
    private var graphName = ""
    private var capture: ImageCapture? = null

    /** Horizontal field of view of the upright photo; replaced from the lens when known. */
    private var hfovRad = Math.toRadians(DEFAULT_HFOV_DEG)
    private var pose: CameraPose? = null
    private val yawHistory = ArrayDeque<Pair<Long, Double>>()
    private var trueOffset: Double? = null

    // One scan at a time, owned by the main thread.
    private var scanning = false

    /** Bumped per scan so late results from a cancelled scan are ignored. */
    private var scanId = 0
    private var capturing = false
    private var startYaw = 0.0
    private var nextPhoto = 0
    private var nodeId = ""
    private var scanDir: File? = null
    private val frames = mutableListOf<JSONObject>()
    private val observations = mutableListOf<OrientObservation>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.orient_scan_activity)
        findViewById<View>(R.id.scan_root).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        sensors = getSystemService(SensorManager::class.java)
        guide = findViewById(R.id.scan_guide)
        prompt = findViewById(R.id.scan_prompt)
        result = findViewById(R.id.scan_result)
        startButton = findViewById(R.id.scan_start)
        nodeField = findViewById(R.id.scan_node)
        findViewById<PreviewView>(R.id.scan_preview).scaleType = PreviewView.ScaleType.FIT_CENTER
        val camera = findViewById<FrameLayout>(R.id.scan_camera)
        camera.post {
            camera.layoutParams = camera.layoutParams.apply { height = camera.width * 4 / 3 }
        }
        loadGraph()
        startButton.setOnClickListener {
            hideKeyboard()
            if (scanning) cancelScan() else startScan()
        }
        findViewById<Button>(R.id.scan_truth).setOnClickListener {
            hideKeyboard()
            askTrueDirection()
        }
        nodeField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) hideKeyboard()
            false
        }
        // Tapping the camera view means "done typing".
        camera.setOnClickListener { hideKeyboard() }
        prompt.setText(R.string.scan_ready)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, main)
        }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        if (scanning) cancelScan()
        super.onPause()
    }

    override fun onDestroy() {
        analysis.shutdown()
        recognizer.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            prompt.setText(R.string.scan_need_camera)
        }
    }

    /** The keyboard would cover the guide and the result; only show it while typing. */
    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(nodeField.windowToken, 0)
        nodeField.clearFocus()
    }

    private fun loadGraph() {
        val name = assets.list("graphs").orEmpty().filter { it.endsWith(".graph.json") }
            .sortedBy { if (it.startsWith("eh-3-elevators")) 0 else 1 }.firstOrNull()
        graph = name?.let { FloorGraphJson.decode(assets.open("graphs/$it").reader().readText()) }
        graphName = name.orEmpty()
        findViewById<TextView>(R.id.scan_graph).text =
            name?.let { getString(R.string.scan_graph, it) } ?: getString(R.string.scan_no_graph)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // 4:3 matches the sensor, so the photo is uncropped and its field of view is the lens's.
            val ratio = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                ).build()
            val preview = Preview.Builder().setResolutionSelector(ratio).build()
            preview.setSurfaceProvider(findViewById<PreviewView>(R.id.scan_preview).surfaceProvider)
            val still = ImageCapture.Builder().setResolutionSelector(ratio)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                still
            )
            capture = still
            fieldOfView(camera.cameraInfo)?.let { hfovRad = it }
        }, mainExecutor)
    }

    /** Horizontal field of view of upright portrait photos, which span the sensor's short side. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun fieldOfView(camera: CameraInfo): Double? {
        val info = Camera2CameraInfo.from(camera)
        val focal = info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
        )?.firstOrNull() ?: return null
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        return if (focal > 0) 2 * atan(minOf(sensor.width, sensor.height) / (2.0 * focal)) else null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = CameraPose.fromRotationVector(event.values) ?: return
        pose = now
        yawHistory.addLast(event.timestamp to now.yawRad)
        val horizon = config.orientCaptureSteadyMs * 2_000_000
        while (yawHistory.size > 2 && event.timestamp - yawHistory.first().first > horizon) {
            yawHistory.removeFirst()
        }
        updateGuide(now, event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** True if the camera turned less than the steady threshold over the steady window. */
    private fun steady(nowNs: Long, yaw: Double): Boolean {
        val windowNs = config.orientCaptureSteadyMs * 1_000_000
        if (yawHistory.isEmpty() || nowNs - yawHistory.first().first < windowNs) return false
        val limit = Math.toRadians(config.orientCaptureSteadyDeg)
        return yawHistory.filter { nowNs - it.first <= windowNs }
            .all { abs(wrapHeading(it.second - yaw)) <= limit }
    }

    private fun updateGuide(now: CameraPose, timestampNs: Long) {
        val focalView = guide.width / 2.0 / tan(hfovRad / 2)
        val horizonY = (focalView * tan(now.pitchRad)).toFloat()
        if (!scanning) {
            guide.show(null, false, horizonY)
            return
        }
        if (capturing) return
        val photos = config.orientScanPhotos
        val target = wrapHeading(startYaw + nextPhoto * 2 * Math.PI / photos)
        val error = wrapHeading(target - now.yawRad)
        val upright = abs(Math.toDegrees(now.pitchRad)) <= config.orientCaptureMaxPitchDeg &&
            abs(Math.toDegrees(now.rollRad)) <= config.orientCaptureMaxRollDeg
        val aimed = abs(Math.toDegrees(error)) <= config.orientCaptureYawToleranceDeg
        // The target sits on the horizon, beside the centre by how far there is left to turn.
        val dx = if (abs(error) < NEAR_SIDEWAYS) focalView * tan(error) else guide.width.toDouble()
        guide.show(
            (if (error < 0) -abs(dx) else abs(dx)).toFloat() to horizonY,
            aimed && upright,
            horizonY,
        )
        val n = nextPhoto + 1
        prompt.text = when {
            !upright -> getString(R.string.scan_hold_upright)
            !aimed && error > 0 -> getString(R.string.scan_turn_right, n, photos)
            !aimed -> getString(R.string.scan_turn_left, n, photos)
            !steady(timestampNs, now.yawRad) -> getString(R.string.scan_hold_still, n, photos)
            else -> {
                takePhoto(now)
                getString(R.string.scan_capturing, n, photos)
            }
        }
    }

    private fun startScan() {
        val g = graph ?: return
        val typed = nodeField.text.toString().trim()
        val node = g.nodes.keys.firstOrNull { it.equals(typed, ignoreCase = true) }
            ?: matchRoomLabel(typed, g.landmarks.map { it.label }.toSet())
                ?.let { label -> g.landmarks.first { it.label.equals(label, true) }.nodeId }
        val current = pose
        if (node == null || capture == null || current == null) {
            prompt.setText(if (node == null) R.string.scan_need_node else R.string.scan_need_camera)
            return
        }
        nodeId = node
        scanId++
        startYaw = current.yawRad
        nextPhoto = 0
        frames.clear()
        observations.clear()
        scanDir = File(filesDir, "scans/scan-${System.currentTimeMillis()}").apply { mkdirs() }
        scanning = true
        nodeField.isEnabled = false
        startButton.setText(R.string.scan_cancel)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        result.text = ""
    }

    private fun cancelScan() {
        scanning = false
        capturing = false
        nodeField.isEnabled = true
        startButton.setText(R.string.scan_start)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prompt.setText(R.string.scan_ready)
    }

    private fun takePhoto(atTrigger: CameraPose) {
        val still = capture ?: return
        capturing = true
        val index = nextPhoto
        still.takePicture(
            mainExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap = image.toBitmap()
                    image.close()
                    capturing = false
                    if (!scanning) return
                    // Turning during the exposure would blur and mis-date the photo: retake.
                    val after = pose
                    if (after != null &&
                        abs(Math.toDegrees(wrapHeading(after.yawRad - atTrigger.yawRad))) >
                        config.orientCaptureSteadyDeg * 2
                    ) {
                        return
                    }
                    guide.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    nextPhoto++
                    val dir = checkNotNull(scanDir)
                    val id = scanId
                    val fov = hfovRad
                    analysis.execute { analyse(id, index, bitmap, rotation, atTrigger, fov, dir) }
                    if (nextPhoto == config.orientScanPhotos) {
                        scanning = false
                        nodeField.isEnabled = true
                        startButton.setText(R.string.scan_start)
                        prompt.setText(R.string.scan_analysing)
                        guide.show(null, false, null)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                }
            },
        )
    }

    /** Runs on the analysis thread: save, OCR, vanishing point, then hand back to main. */
    private fun analyse(
        id: Int,
        index: Int,
        raw: Bitmap,
        rotation: Int,
        pose: CameraPose,
        hfov: Double,
        dir: File
    ) {
        val upright = if (rotation == 0) {
            raw
        } else {
            Bitmap.createBitmap(
                raw,
                0,
                0,
                raw.width,
                raw.height,
                Matrix().apply {
                    postRotate(rotation.toFloat())
                },
                true
            )
        }
        val frame = ScanFrame(pose, upright.width, upright.height, hfov)
        val json = JSONObject().put("index", index).put("yawRad", pose.yawRad)
            .put("pitchRad", pose.pitchRad).put("rollRad", pose.rollRad)
            .put("width", upright.width).put("height", upright.height)
        try {
            val scale = SAVE_LONG_SIDE.toFloat() / maxOf(upright.width, upright.height)
            Bitmap.createScaledBitmap(
                upright,
                (upright.width * scale).roundToInt(),
                (upright.height * scale).roundToInt(),
                true,
            ).let { small ->
                File(dir, "frame-$index.jpg").outputStream().use {
                    small.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
            }
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(upright, 0)))
            val hits = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                line.boundingBox?.let { TextHit(line.text, it.exactCenterX().toDouble()) }
            }
            val vp = vanishingPoint(upright, pose, hfov)
            val labels = graph?.landmarks?.map { it.label }?.toSet().orEmpty()
            val found = scanObservations(frame, vp, hits, labels, config)
            json.put(
                "texts",
                JSONArray(
                    hits.map {
                        JSONObject().put("text", it.text).put("centerX", it.centerX)
                    }
                )
            )
            json.put(
                "vanishingPoint",
                vp?.let {
                    JSONObject().put("angleRad", it.angleRad).put("strength", it.strength)
                        .put("inView", it.inView)
                } ?: JSONObject.NULL,
            )
            json.put("observations", JSONArray(found.map(::describe)))
            main.post { frameDone(id, json, found) }
        } catch (error: Exception) {
            json.put("error", error.toString())
            main.post { frameDone(id, json, emptyList()) }
        }
    }

    private fun vanishingPoint(upright: Bitmap, pose: CameraPose, hfov: Double): VanishingPoint? {
        val w = VP_WIDTH
        val h = upright.height * w / upright.width
        val small = Bitmap.createScaledBitmap(upright, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h) {
            val p = pixels[it]
            val luma = 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) +
                0.114f * (p and 0xFF)
            luma / 255f
        }
        return finder.find(gray, ScanFrame(pose, w, h, hfov))
    }

    private fun frameDone(id: Int, json: JSONObject, found: List<OrientObservation>) {
        if (id != scanId) return
        frames += json
        observations += found
        if (frames.size < config.orientScanPhotos || scanning) return
        val g = graph ?: return
        val solved = solver.solve(g, nodeId, observations)
        val facing = pose?.yawRad
        result.text = report(solved, facing)
        prompt.setText(R.string.scan_done)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saveScan(solved)
        // The game-rotation zero can drift between scans: ask for a fresh truth each time.
        trueOffset = null
    }

    private fun report(solved: OrientResult, facingYaw: Double?): String = buildString {
        appendLine("Result: ${solved.status} · ${solved.usedObservations} usable observations")
        val best = solved.best
        if (best != null && facingYaw != null) {
            val map = Math.toDegrees(wrapHeading(facingYaw + best.offsetRad))
            appendLine("Camera now faces map ${fmt(map)}° (${arrow(map)})")
        }
        solved.candidates.forEachIndexed { i, c ->
            appendLine(
                "  #${i + 1} offset ${fmt(
                    Math.toDegrees(c.offsetRad)
                )}° · score ${fmt(c.logLikelihood)}"
            )
        }
        val doors = observations.filterIsInstance<OrientObservation.Door>().map { it.label }
        appendLine("Doors read: ${doors.distinct().joinToString().ifEmpty { "none" }}")
        val axes = observations.count { it is OrientObservation.CorridorAxis }
        val openings = observations.count { it is OrientObservation.Opening }
        appendLine("Corridor lines in $axes of ${frames.size} photos ($openings looking down one)")
        trueOffset?.let { truth ->
            best?.let {
                appendLine(
                    "Error vs true direction: ${fmt(
                        Math.toDegrees(abs(wrapHeading(it.offsetRad - truth)))
                    )}°"
                )
            }
        }
        append("Saved ${scanDir?.name}")
    }

    private fun saveScan(solved: OrientResult) {
        val dir = scanDir ?: return
        val json = JSONObject()
            .put("formatVersion", 1)
            .put("graph", graphName)
            .put("nodeId", nodeId)
            .put("hfovRad", hfovRad)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("trueOffsetRad", trueOffset ?: JSONObject.NULL)
            .put("frames", JSONArray(frames.sortedBy { it.getInt("index") }))
            .put("status", solved.status.name)
            .put(
                "candidates",
                JSONArray(
                    solved.candidates.map {
                        JSONObject().put(
                            "offsetRad",
                            it.offsetRad
                        ).put("logLikelihood", it.logLikelihood)
                    },
                ),
            )
        analysis.execute { File(dir, "scan.json").writeText(json.toString(2)) }
    }

    private fun askTrueDirection() {
        AlertDialog.Builder(this).setTitle(R.string.scan_truth)
            .setItems(R.array.recorder_map_directions) { _, choice ->
                val bearings = doubleArrayOf(-Math.PI / 2, 0.0, Math.PI / 2, Math.PI)
                val now = pose ?: return@setItems
                trueOffset = wrapHeading(bearings[choice] - now.yawRad)
                prompt.setText(R.string.scan_truth_set)
            }.show()
    }

    private companion object {
        const val CAMERA_REQUEST = 3
        const val DEFAULT_HFOV_DEG = 60.0
        const val VP_WIDTH = 320
        const val SAVE_LONG_SIDE = 1600

        /** Past this the target is off screen; pin the dot to the edge instead of tan → ∞. */
        const val NEAR_SIDEWAYS = Math.PI / 2 - 0.05

        fun fmt(value: Double) = String.format(Locale.ROOT, "%.1f", value)

        fun arrow(mapDeg: Double) = when (((mapDeg + 45).mod(360.0) / 90).toInt()) {
            0 -> "→ map right"
            1 -> "↓ map down"
            2 -> "← map left"
            else -> "↑ map up"
        }

        fun describe(o: OrientObservation): JSONObject = JSONObject()
            .put("kind", o::class.simpleName)
            .put("relativeRad", o.relativeRad)
            .put("label", (o as? OrientObservation.Door)?.label ?: JSONObject.NULL)
    }
}
