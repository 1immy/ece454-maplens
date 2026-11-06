package edu.wisc.maplens.mapparse

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect as AndroidRect
import android.media.ExifInterface
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.nearestEdge
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** Fixture runner. Artifacts are written to files/poster-eval in the test APK's sandbox. */
class PosterParserChecks : Instrumentation() {
    private val output by lazy { File(targetContext.filesDir, "poster-eval").apply { mkdirs() } }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            requireOpenCv()
            checkBitmapColors()
            checkBadPhoto()
            checkFadedFrame()
            checkWarmGoldStar()
            checkFaintMarkerCorridor()
            checkExitStubs()
            checkUnsupportedDoorDetour()
            checkSeparateDoorways()
            checkFrames()
            checkRectified("eh-3-elevators-evac")
            checkRectified("eh-3-stair3424-evac")
            checkCapture("eh-3-elevators-evac-03-front")
            checkCapture("eh-3-stair-3424-evac-01-front")
            checkSmallFrames()
            checkEveryPhoto()
            finish(
                Activity.RESULT_OK,
                Bundle().apply { putString("stream", "Poster checks passed") }
            )
        } catch (failure: Exception) {
            Log.e("PosterParserChecks", "Fixture run failed", failure)
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString("stream", Log.getStackTraceString(failure))
                }
            )
        }
    }

    private fun checkBitmapColors() = Mats().use { mats ->
        val blue = mats.own(Mat(1, 1, CvType.CV_8UC3, Scalar(214.0, 62.0, 13.0)))
        val bitmap = bitmapFromBgr(blue)
        try {
            check(bitmap.getPixel(0, 0) == Color.rgb(13, 62, 214))
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkBadPhoto() {
        val blank = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
            blank.eraseColor(-1)
            val failure = runCatching { OfflineMapParser().parse(blank) }.exceptionOrNull()
            check(failure is PosterRetakeException)
        } finally {
            blank.recycle()
        }
    }

    private fun checkFadedFrame() = Mats().use { mats ->
        val photo = mats.own(Mat(750, 1000, CvType.CV_8UC3, Scalar(198.0, 198.0, 198.0)))
        Imgproc.rectangle(
            photo,
            Point(100.0, 125.0),
            Point(900.0, 625.0),
            Scalar(211.0, 200.0, 192.0),
            20
        )
        val corners = PosterFrame().corners(photo)
        check(corners[0].x in 85.0..105.0 && corners[0].y in 110.0..130.0)
        check(corners[2].x in 895.0..915.0 && corners[2].y in 620.0..640.0)
    }

    private fun checkWarmGoldStar() = Mats().use { mats ->
        val hsv = mats.own(Mat.zeros(180, 180, CvType.CV_8UC3))
        Imgproc.circle(hsv, Point(90.0, 90.0), 42, Scalar(110.0, 180.0, 140.0), -1)
        val points = (0 until 10).map { index ->
            val angle = -Math.PI / 2 + index * Math.PI / 5
            val radius = if (index % 2 == 0) 32.0 else 14.0
            Point(90 + radius * kotlin.math.cos(angle), 90 + radius * kotlin.math.sin(angle))
        }
        val star = mats.own(MatOfPoint(*points.toTypedArray()))
        Imgproc.fillPoly(hsv, listOf(star), Scalar(11.0, 80.0, 180.0))
        check(detectStars(hsv).single().distanceTo(PixelPoint(90.0, 90.0)) < 4.0)
        val mixed = mats.own(Mat.zeros(180, 400, CvType.CV_8UC3))
        hsv.copyTo(mats.own(mixed.submat(0, 180, 0, 180)))
        val strict = mats.own(hsv.clone())
        Imgproc.fillPoly(strict, listOf(star), Scalar(22.0, 180.0, 180.0))
        strict.copyTo(mats.own(mixed.submat(0, 180, 200, 380)))
        check(detectStars(mixed).size == 2)
        Imgproc.circle(hsv, Point(90.0, 90.0), 34, Scalar(11.0, 80.0, 180.0), -1)
        check(detectStars(hsv).isEmpty())
    }

    private fun checkFaintMarkerCorridor() = Mats().use { mats ->
        val hsv = mats.own(Mat.zeros(500, 600, CvType.CV_8UC3))
        Imgproc.line(hsv, Point(150.0, 50.0), Point(150.0, 450.0), Scalar(8.0, 180.0, 180.0), 5)
        val routes = mats.mat()
        routeMask(hsv, routes, mats)
        val initial = PosterCorridors().extract(routes)
        val marker = PixelPoint(300.0, 250.0)
        check(recoverMarkerCorridor(hsv, routes, initial, listOf(marker)) == initial)
        Imgproc.line(hsv, Point(150.0, 250.0), Point(550.0, 250.0), Scalar(8.0, 65.0, 180.0), 5)
        val recovered = recoverMarkerCorridor(hsv, routes, initial, listOf(marker))
        check(checkNotNull(recovered.nearestEdge(marker)).distancePx < 5.0)
        check(routes.get(250, 300)[0] > 0)
    }

    private fun checkExitStubs() = Mats().use { mats ->
        val routes = mats.own(Mat.zeros(300, 600, CvType.CV_8U))
        Imgproc.line(routes, Point(50.0, 100.0), Point(550.0, 100.0), Scalar(255.0), 5)
        Imgproc.line(routes, Point(300.0, 100.0), Point(300.0, 170.0), Scalar(255.0), 5)
        val tip = PixelPoint(300.0, 170.0)
        check(checkNotNull(PosterCorridors().extract(routes).nearestEdge(tip)).distancePx < 15.0)
        val atStar = PosterCorridors().extract(routes, listOf(tip))
        check(checkNotNull(atStar.nearestEdge(tip)).distancePx > 50.0)
    }

    private fun checkUnsupportedDoorDetour() {
        val a = Node("a", PixelPoint(49.0, 50.0), NodeType.DEAD_END)
        val b = Node("b", PixelPoint(51.0, 50.0), NodeType.DEAD_END)
        val edge = Edge("e", a.id, b.id, listOf(a.position, b.position), 2.0)
        val draft = FloorGraph(
            "engineering-hall",
            "3",
            mapOf(a.id to a, b.id to b),
            listOf(edge),
            emptyList(),
            "",
        )
        val walls = ByteArray(100 * 100)
        for (y in 25..34) for (x in 15..85) walls[y * 100 + x] = 1
        val room = PosterReading("3418", AndroidRect(48, 8, 52, 12), 0.9f)
        val warnings = mutableListOf<String>()
        val result = draft.attachRooms(listOf(room), walls, 100, warnings)
        check(result.graph.landmarks.isEmpty())
        val preview = result.rooms.single()
        check(preview.label == room.text && preview.bounds == room.box)
        check(preview.entranceNodeId == null)
        check(warnings.single().contains("long detour"))
    }

    private fun checkSeparateDoorways() {
        val a = Node("a", PixelPoint(10.0, 75.0), NodeType.DEAD_END)
        val b = Node("b", PixelPoint(130.0, 75.0), NodeType.DEAD_END)
        val edge = Edge("e", a.id, b.id, listOf(a.position, b.position), 120.0)
        val graph = FloorGraph(
            "engineering-hall",
            "3",
            mapOf(a.id to a, b.id to b),
            listOf(edge),
            emptyList(),
            "",
        )
        val width = 140
        val walls = ByteArray(width * 90)
        for (y in 40..47) for (x in 0 until width) {
            if (x !in 100..107 && (x !in 46..54 || y == 44)) walls[y * width + x] = 1
        }
        val rooms = listOf(
            PosterReading("3615", AndroidRect(48, 18, 52, 22), 0.9f),
            PosterReading("3617", AndroidRect(101, 18, 105, 22), 0.9f),
        )
        val warnings = mutableListOf<String>()
        val result = graph.attachRooms(rooms, walls, width, warnings)
        val positions = result.graph.landmarks.associate {
            it.label to result.graph.nodes.getValue(it.nodeId).position.x
        }
        check(positions.getValue("3615") in 46.0..54.0)
        check(positions.getValue("3617") in 100.0..107.0)
        check(warnings.isEmpty())
    }

    private fun checkFrames() {
        val results = JSONObject()
        val photos = context.assets.list("").orEmpty().filter {
            it.endsWith(".jpg") && it.matches(Regex(".*-[0-9]{2}-.*"))
        }
        check(photos.size >= 16)
        for (photo in photos) {
            val bitmap = load(photo)
            try {
                Mats().use { mats ->
                    val corners = PosterFrame().corners(bitmapBgr(bitmap, mats))
                    results.put(photo, JSONArray(corners.map { JSONArray(listOf(it.x, it.y)) }))
                    check(!photo.contains("-crop"))
                }
            } catch (failure: PosterRetakeException) {
                results.put(photo, failure.message)
                check(photo.contains("-crop")) { "$photo: ${failure.message}" }
            } finally {
                bitmap.recycle()
            }
        }
        File(output, "frames.json").writeText(results.toString(2))
    }

    private fun checkRectified(poster: String) {
        val bitmap = load("$poster.jpg")
        try {
            Mats().use { mats ->
                val bgr = bitmapBgr(bitmap, mats)
                val hsv = mats.mat()
                Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
                val routes = mats.mat()
                routeMask(hsv, routes, mats)
                val stars = detectStars(hsv)
                val corridors = PosterCorridors().extract(routes, stars)
                checkGeometry(corridors)
                writeGraph("$poster.corridors", corridors)
                val readings = recognizePoster(bgr)
                val rooms = distinctRooms(readings)
                check(rooms.isNotEmpty())
                writeReadings(poster, readings)
                File(output, "$poster.walls.bin").writeBytes(wallMask(bgr, routes, readings))
                val marker = stars.single()
                if (poster == "eh-3-elevators-evac") {
                    checkStarCounts(
                        hsv,
                        marker.x.toInt(),
                        marker.y.toInt(),
                        mats
                    )
                }
                val corridorStart = resolveStart(corridors, stars, mutableListOf())
                val corridorPoint = corridorStart.nodes.getValue(corridorStart.startNodeId).position
                val result = OfflineMapParser().parseRectified(bitmap, bgr)
                check(result.rectified === bitmap)
                check(result.rooms.count { !it.possible } == rooms.size)
                val named = result.graph.landmarks.map { it.label }
                check(result.rooms.filter { it.possible }.none { it.label in named })
                val landmarks = result.graph.landmarks.map { it.label to it.nodeId }.toSet()
                for ((label, bounds, nodeId) in result.rooms.filterNot { it.possible }) {
                    check(!bounds.isEmpty)
                    check(rooms.any { it.text == label && it.box == bounds })
                    if (nodeId != null) check(label to nodeId in landmarks)
                }
                checkGeometry(result.graph)
                if (poster == "eh-3-elevators-evac") {
                    val room = result.graph.landmarks.single { it.label == "3332" }
                    val entrance = result.graph.nodes.getValue(room.nodeId).position
                    check(entrance.distanceTo(PixelPoint(915.6, 1359.9)) <= 40.0)
                }
                writeGraph("$poster.final", result.graph)
                check(result.graph.landmarks.isNotEmpty())
                val start = result.graph.nodes[result.graph.startNodeId]
                val report = JSONObject()
                    .put("marker", JSONArray(listOf(marker.x, marker.y)))
                    .put("corridorOnlySnapPx", corridorPoint.distanceTo(marker))
                    .put("snapPx", start?.position?.distanceTo(marker))
                    .put("warnings", JSONArray(result.warnings))
                    .put("validation", JSONArray(result.graph.validate()))
                File(output, "$poster.start.json").writeText(report.toString(2))
                if (start == null && poster == "eh-3-stair3424-evac") {
                    check(result.warnings.any { "overlaps a room or landmark" in it })
                    check(result.graph.landmarks.any { it.label == "3424" })
                    check(result.graph.validate().single().contains("start node"))
                } else {
                    check(start?.type == NodeType.START) { report.toString() }
                    check(result.graph.validate().isEmpty())
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeReadings(name: String, readings: List<PosterReading>) {
        fun words(entries: List<PosterReading>) = JSONArray(
            entries.map {
                val box = it.box
                JSONObject().put("text", it.text).put("confidence", it.confidence)
                    .put("box", JSONArray(listOf(box.left, box.top, box.width(), box.height())))
            },
        )
        val audit = JSONObject().put("labels", words(distinctRooms(readings)))
            .put("raw", words(readings))
        File(output, "$name.ocr.json").writeText(audit.toString(2))
    }

    private fun checkStarCounts(hsv: Mat, x: Int, y: Int, mats: Mats) {
        val patch = mats.own(hsv.submat(Rect(x - 40, y - 40, 80, 80)))
        val canvas = mats.own(Mat.zeros(200, 300, hsv.type()))
        check(detectStars(canvas).isEmpty())
        patch.copyTo(mats.own(canvas.submat(Rect(10, 10, 80, 80))))
        patch.copyTo(mats.own(canvas.submat(Rect(100, 100, 80, 80))))
        check(detectStars(canvas).size == 2)
    }

    private fun checkCapture(photo: String) {
        val bitmap = load("$photo.jpg", maxSide = 3200)
        try {
            val corners = Mats().use { mats ->
                PosterFrame().corners(bitmapBgr(bitmap, mats))
            }
            val before = Debug.getPss()
            val start = SystemClock.elapsedRealtime()
            val stages = mutableListOf<ParseStage>()
            val result = OfflineMapParser { stages += it.stage }.parse(bitmap)
            val elapsed = SystemClock.elapsedRealtime() - start
            check(stages.distinct() == ParseStage.entries) { stages.distinct() }
            check(stages == stages.sorted())
            val after = Debug.getPss()
            val graph = result.graph
            checkGeometry(graph)
            writeGraph("$photo.capture", graph)
            val report = JSONObject().put("elapsedMs", elapsed)
                .put("beforePssKiB", before).put("afterPssKiB", after)
                .put("peakRssKiB", peakRssKiB())
                .put("corners", JSONArray(corners.map { JSONArray(listOf(it.x, it.y)) }))
                .put("sourceSize", JSONArray(listOf(bitmap.width, bitmap.height)))
                .put("width", result.rectified.width).put("height", result.rectified.height)
                .put("warnings", JSONArray(result.warnings))
                .put("validation", JSONArray(graph.validate()))
            File(output, "$photo.capture.json").writeText(report.toString(2))
            val rectified = File(output, "$photo.flat.png")
            rectified.outputStream().use {
                result.rectified.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Mats().use { mats ->
                val image = result.rectified
                writeReadings(photo, recognizePoster(bitmapBgr(image, mats)))
            }
            result.rectified.recycle()
            check(graph.landmarks.isNotEmpty())
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkSmallFrames() {
        val photo = "eh-3-elevators-evac-03-front.jpg"
        val tiny = load(photo, maxSide = 1000)
        try {
            val failure = runCatching { OfflineMapParser().parse(tiny) }.exceptionOrNull()
            check(failure is PosterRetakeException && "too small" in failure.message.orEmpty())
        } finally {
            tiny.recycle()
        }
        val small = load(photo, maxSide = 1800)
        try {
            val result = OfflineMapParser().parse(small)
            check(result.warnings.any { "misread" in it })
            result.rectified.recycle()
        } finally {
            small.recycle()
        }
    }

    /** Every photo ends as a draft or a retake. Directory posters and crops are never drafts. */
    private fun checkEveryPhoto() {
        val reports = JSONObject()
        val photos = context.assets.list("").orEmpty().filter {
            it.endsWith(".jpg") && it.matches(Regex(".*-[0-9]{2}-.*"))
        }
        for (photo in photos) {
            val name = photo.removeSuffix(".jpg")
            val bitmap = load(photo, maxSide = 3200)
            val report = JSONObject()
            try {
                val quad = runCatching {
                    Mats().use { mats -> PosterFrame().corners(bitmapBgr(bitmap, mats)) }
                }.getOrNull()
                val start = SystemClock.elapsedRealtime()
                val result = OfflineMapParser().parse(bitmap)
                check("-evac-" in photo) { "$photo must be retaken" }
                checkGeometry(result.graph)
                check(result.warnings.none { "Gold star not found" in it || "Several" in it }) {
                    "$photo: ${result.warnings}"
                }
                check(("-blur" in photo) == result.warnings.any { "misread" in it }) { photo }
                writeGraph("$name.photo", result.graph)
                report.put("elapsedMs", SystemClock.elapsedRealtime() - start)
                    .put("peakRssKiB", peakRssKiB())
                    .put("corners", JSONArray(quad?.map { JSONArray(listOf(it.x, it.y)) }))
                    .put("sourceSize", JSONArray(listOf(bitmap.width, bitmap.height)))
                    .put("width", result.rectified.width).put("height", result.rectified.height)
                    .put("warnings", JSONArray(result.warnings))
                result.rectified.recycle()
            } catch (failure: PosterRetakeException) {
                check("-evac-" !in photo) { "$photo: ${failure.message}" }
                report.put("retake", failure.message)
            } finally {
                bitmap.recycle()
            }
            reports.put(name, report)
        }
        File(output, "photos.json").writeText(reports.toString(2))
    }

    /** Process-wide peak since launch. Snapshots of PSS miss the detector's transient buffers. */
    private fun peakRssKiB(): Long = File("/proc/self/status").readLines()
        .first { it.startsWith("VmHWM") }.filter(Char::isDigit).toLong()

    private fun load(name: String, maxSide: Int? = null): Bitmap {
        val orientation = context.assets.open(name).use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val bitmap = context.assets.open(name).use { checkNotNull(BitmapFactory.decodeStream(it)) }
        val scale = if (maxSide == null) {
            1f
        } else {
            minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        }
        if (rotation == 0 && scale == 1f) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(scale, scale)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { bitmap.recycle() }
    }

    private fun checkGeometry(graph: FloorGraph) {
        check(!graph.verified)
        check(graph.edges.isNotEmpty())
        check(graph.edges.map { it.id }.distinct().size == graph.edges.size)
        for (edge in graph.edges) {
            check(edge.polyline.first() == graph.nodes.getValue(edge.from).position)
            check(edge.polyline.last() == graph.nodes.getValue(edge.to).position)
            check(edge.lengthPx.isFinite() && edge.lengthPx > 0)
        }
        check(graph.landmarks.all { it.nodeId in graph.nodes })
    }

    private fun writeGraph(name: String, graph: FloorGraph) {
        File(output, "$name.graph.json").writeText(FloorGraphJson.encode(graph))
    }
}
