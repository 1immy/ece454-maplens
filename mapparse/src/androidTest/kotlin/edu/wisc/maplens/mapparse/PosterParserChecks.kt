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
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private val PAPER = Scalar(235.0, 238.0, 238.0)
private val ROUTE = Scalar(40.0, 80.0, 190.0)
private val WALL = Scalar(40.0, 40.0, 40.0)

/** Fixture runner. Artifacts are written to files/poster-eval in the test APK's sandbox. */
class PosterParserChecks : Instrumentation() {
    private val output by lazy { File(targetContext.filesDir, "poster-eval").apply { mkdirs() } }

    private var probe: String? = null
    private var photo: String? = null
    private var compare: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        probe = arguments?.getString("probe")
        photo = arguments?.getString("photo")
        compare = arguments?.getString("compare")
        start()
    }

    override fun onStart() {
        probe?.let { path ->
            runProbe(path)
            return
        }
        photo?.let { path ->
            runPhoto(path)
            return
        }
        compare?.let { path ->
            runCompare(path)
            return
        }
        try {
            requireOpenCv()
            checkFaintPaint()
            checkNoPaintCorridors()
            checkPinkRoomsAreNotPaint()
            checkStripedPinkRooms()
            checkExitSignAndDarkPaint()
            checkScreenPhoto()
            checkHallways()
            checkColourJoin()
            checkLadder()
            checkUnpaintedLink()
            checkLinkStopsAtDoor()
            checkStraightCorridor()
            checkDirectoryPoster()
            checkBitmapColors()
            checkBadPhoto()
            checkFadedFrame()
            checkWarmGoldStar()
            checkFaintMarkerCorridor()
            checkExitStubs()
            checkWallFringe()
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

    /** Same layout as tools/test_corridors.py: fringe beside paint goes, a lone faint line stays. */
    private fun checkWallFringe() = Mats().use { mats ->
        val hsv = mats.own(Mat(400, 1200, CvType.CV_8UC3, Scalar(0.0, 0.0, 238.0)))
        for (x in listOf(570.0, 630.0, 900.0)) {
            Imgproc.line(hsv, Point(x, 20.0), Point(x, 380.0), Scalar(0.0, 0.0, 40.0), 4)
        }
        Imgproc.line(hsv, Point(600.0, 20.0), Point(600.0, 380.0), Scalar(8.0, 200.0, 190.0), 6)
        for (x in listOf(573.0, 627.0, 903.0)) {
            Imgproc.line(hsv, Point(x, 40.0), Point(x, 360.0), Scalar(10.0, 90.0, 120.0), 2)
        }
        val routes = mats.mat()
        routeMask(hsv, routes, mats)
        check(routes.get(200, 600)[0] > 0)
        check(routes.get(200, 573)[0] == 0.0)
        check(routes.get(200, 627)[0] == 0.0)
        check(routes.get(200, 903)[0] > 0)
    }

    private fun routesOf(bgr: Mat, mats: Mats): Mat {
        val hsv = mats.mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val routes = mats.mat()
        routeMask(hsv, routes, mats)
        return routes
    }

    private fun networkCount(graph: FloorGraph): Int {
        val parent = graph.nodes.keys.associateWith { it }.toMutableMap()
        fun find(start: String): String {
            var node = start
            while (parent.getValue(node) != node) node = parent.getValue(node)
            return node
        }
        for (edge in graph.edges) parent[find(edge.from)] = find(edge.to)
        return graph.nodes.keys.map(::find).toSet().size
    }

    /** Same layouts as tools/test_corridors.py. */
    private fun paperMat(mats: Mats, rows: Int = 800): Mat =
        mats.own(Mat(rows, 1200, CvType.CV_8UC3, Scalar(235.0, 238.0, 238.0)))

    private fun drawLine(img: Mat, a: Point, b: Point, color: Scalar, thickness: Int) =
        Imgproc.line(img, a, b, color, thickness)

    private fun checkFaintPaint() = Mats().use { mats ->
        val img = paperMat(mats, rows = 400)
        val faint = mats.own(Mat(1, 1, CvType.CV_8UC3, Scalar(8.0, 55.0, 190.0)))
        Imgproc.cvtColor(faint, faint, Imgproc.COLOR_HSV2BGR)
        val color = Scalar(faint.get(0, 0))
        drawLine(img, Point(100.0, 100.0), Point(400.0, 100.0), ROUTE, 6)
        drawLine(img, Point(400.0, 100.0), Point(800.0, 100.0), color, 6)
        drawLine(img, Point(100.0, 300.0), Point(500.0, 300.0), color, 6)
        drawLine(img, Point(600.0, 40.0), Point(600.0, 90.0), WALL, 4)
        val routes = routesOf(img, mats)
        check(routes.get(100, 700)[0] > 0) { "faint paint joined to solid paint" }
        check(routes.get(300, 300)[0] == 0.0) { "faint paint on its own" }
    }

    private fun walledCorridors(mats: Mats): Mat {
        val img = mats.own(Mat(800, 1200, CvType.CV_8UC3, Scalar(200.0, 170.0, 150.0)))
        Imgproc.rectangle(img, Point(100.0, 100.0), Point(1100.0, 700.0), PAPER, -1)
        drawLine(img, Point(100.0, 300.0), Point(1100.0, 300.0), WALL, 3)
        drawLine(img, Point(100.0, 340.0), Point(580.0, 340.0), WALL, 3)
        drawLine(img, Point(620.0, 340.0), Point(1100.0, 340.0), WALL, 3)
        for (x in listOf(580.0, 620.0, 250.0, 400.0, 770.0, 920.0)) {
            drawLine(img, Point(x, 340.0), Point(x, 700.0), WALL, 3)
        }
        Imgproc.rectangle(img, Point(100.0, 100.0), Point(1100.0, 700.0), WALL, 3)
        for (x in 250 until 1100 step 150) {
            drawLine(img, Point(x.toDouble(), 100.0), Point(x.toDouble(), 300.0), WALL, 3)
        }
        drawLine(img, Point(100.0, 520.0), Point(580.0, 520.0), WALL, 3)
        drawLine(img, Point(620.0, 520.0), Point(1100.0, 520.0), WALL, 3)
        for (door in listOf(170.0, 320.0, 470.0, 690.0, 840.0, 990.0)) {
            drawLine(img, Point(door, 300.0), Point(door + 12, 300.0), PAPER, 5)
            drawLine(img, Point(door, 340.0), Point(door + 12, 340.0), PAPER, 5)
        }
        return img
    }

    private fun checkNoPaintCorridors() = Mats().use { mats ->
        val img = walledCorridors(mats)
        val graph = PosterCorridors().extract(routesOf(img, mats), emptyList(), img)
        val junctions = graph.nodes.values.filter { it.type == NodeType.JUNCTION }
        check(junctions.size == 1) { graph.nodes }
        check(junctions.single().position.distanceTo(PixelPoint(600.0, 320.0)) < 20.0)
        check(networkCount(graph) == 1)
        check(checkNotNull(graph.nearestEdge(PixelPoint(300.0, 320.0))).distancePx < 15.0)
        check(checkNotNull(graph.nearestEdge(PixelPoint(600.0, 600.0))).distancePx < 15.0)
        check(checkNotNull(graph.nearestEdge(PixelPoint(175.0, 200.0))).distancePx > 80.0)
    }

    /** Same as tools/test_corridors.py: a camera can push pink room fills into the route colour. */
    private fun checkPinkRoomsAreNotPaint() = Mats().use { mats ->
        val img = walledCorridors(mats)
        val pink = mats.own(Mat(1, 1, CvType.CV_8UC3, Scalar(172.0, 90.0, 210.0)))
        Imgproc.cvtColor(pink, pink, Imgproc.COLOR_HSV2BGR)
        val color = pink.get(0, 0)
        for ((x0, x1) in listOf(100 to 250, 250 to 400, 400 to 550, 700 to 850, 850 to 1000)) {
            for ((y0, y1) in listOf(100 to 300, 340 to 520)) {
                for (y in y0 + 4 until y1 - 4) {
                    for (x in x0 + 4 until x1 - 4) if ((x + y) % 2 == 0) img.put(y, x, *color)
                }
            }
        }
        val graph = PosterCorridors().extract(routesOf(img, mats), emptyList(), img)
        check(checkNotNull(graph.nearestEdge(PixelPoint(300.0, 320.0))).distancePx < 15.0)
        check(checkNotNull(graph.nearestEdge(PixelPoint(600.0, 600.0))).distancePx < 15.0)
        check(graph.edges.flatMap { it.polyline }.none { it.y < 290.0 }) { graph.edges }
    }

    /** Same as tools/test_corridors.py: dark paint and an EXIT sign from a phone photo. */
    private fun checkExitSignAndDarkPaint() = Mats().use { mats ->
        val img = paperMat(mats, rows = 600)
        for (y in listOf(270.0, 330.0)) drawLine(img, Point(60.0, y), Point(1140.0, y), WALL, 3)
        val rust = mats.own(Mat(1, 1, CvType.CV_8UC3, Scalar(10.0, 80.0, 115.0)))
        Imgproc.cvtColor(rust, rust, Imgproc.COLOR_HSV2BGR)
        drawLine(img, Point(100.0, 300.0), Point(1100.0, 300.0), Scalar(rust.get(0, 0)), 6)
        val routes = routesOf(img, mats)
        check(freeSpace(img, routes, routes, mats).width > 45)
        Imgproc.rectangle(
            img,
            Point(500.0, 340.0),
            Point(580.0, 370.0),
            Scalar(40.0, 40.0, 200.0),
            -1
        )
        Imgproc.putText(
            img,
            "EXIT",
            Point(505.0, 364.0),
            Imgproc.FONT_HERSHEY_SIMPLEX,
            0.7,
            PAPER,
            2
        )
        drawLine(img, Point(540.0, 300.0), Point(540.0, 340.0), ROUTE, 6)
        val graph = PosterCorridors().extract(routesOf(img, mats), emptyList(), img)
        check(graph.edges.flatMap { it.polyline }.maxOf { it.y } < 345.0) { graph.edges }
    }

    /** Same layouts as tools/test_corridors.py ScreenPhotoTest: thick paint, symbols on paint. */
    private fun checkScreenPhoto() = Mats().use { mats ->
        val crossing = mats.own(Mat(800, 800, CvType.CV_8UC3, PAPER))
        drawLine(crossing, Point(100.0, 400.0), Point(700.0, 400.0), ROUTE, 8)
        drawLine(crossing, Point(400.0, 100.0), Point(400.0, 700.0), ROUTE, 8)
        Imgproc.circle(crossing, Point(400.0, 400.0), 20, ROUTE, -1)
        val joined = PosterCorridors().extract(routesOf(crossing, mats))
        check(networkCount(joined) == 1) { "thick crossing read as a sign: ${joined.edges}" }
        check(checkNotNull(joined.nearestEdge(PixelPoint(400.0, 400.0))).distancePx < 10.0)

        val blurred = mats.own(Mat(400, 400, CvType.CV_8UC3, PAPER))
        drawLine(blurred, Point(200.0, 140.0), Point(200.0, 260.0), ROUTE, 14)
        val line = PosterCorridors().extract(routesOf(blurred, mats))
        check(line.edges.isNotEmpty()) { "blurred line read as a sign" }

        val symbol = mats.own(Mat(600, 1600, CvType.CV_8UC3, PAPER))
        for (y in listOf(270.0, 330.0)) drawLine(symbol, Point(60.0, y), Point(1540.0, y), WALL, 3)
        drawLine(symbol, Point(800.0, 270.0), Point(800.0, 290.0), WALL, 3)
        drawLine(symbol, Point(800.0, 330.0), Point(800.0, 310.0), WALL, 3)
        drawLine(symbol, Point(100.0, 300.0), Point(740.0, 300.0), ROUTE, 6)
        drawLine(symbol, Point(1500.0, 300.0), Point(880.0, 300.0), ROUTE, 6)
        drawLine(symbol, Point(880.0, 300.0), Point(850.0, 280.0), ROUTE, 6)
        val carried = PosterCorridors().extract(routesOf(symbol, mats), emptyList(), symbol)
        check(networkCount(carried) == 1) { "paint cut by a symbol: ${carried.edges}" }
        check(checkNotNull(carried.nearestEdge(PixelPoint(800.0, 300.0))).distancePx < 15.0)
    }

    /** Same layout as tools/test_corridors.py painted_loop: a rounded corner and a hallway end. */
    private fun checkHallways() = Mats().use { mats ->
        val img = mats.own(Mat(1100, 1300, CvType.CV_8UC3, PAPER))
        drawLine(img, Point(100.0, 300.0), Point(705.0, 300.0), ROUTE, 6)
        Imgproc.ellipse(img, Point(705.0, 325.0), Size(25.0, 25.0), 0.0, 270.0, 360.0, ROUTE, 6)
        val paint = listOf(
            intArrayOf(730, 325, 730, 900),
            intArrayOf(500, 900, 1100, 900),
            intArrayOf(1100, 300, 1100, 900),
        )
        val walls = listOf(
            intArrayOf(60, 270, 1130, 270),
            intArrayOf(60, 330, 700, 330),
            intArrayOf(760, 330, 1070, 330),
            intArrayOf(700, 330, 700, 870),
            intArrayOf(760, 330, 760, 870),
            intArrayOf(1070, 330, 1070, 870),
            intArrayOf(1130, 270, 1130, 930),
            intArrayOf(760, 870, 1070, 870),
            intArrayOf(300, 930, 1130, 930),
            intArrayOf(300, 870, 300, 930),
            intArrayOf(60, 270, 60, 330),
        )
        for ((lines, color, thickness) in listOf(Triple(paint, ROUTE, 6), Triple(walls, WALL, 3))) {
            for ((x0, y0, x1, y1) in lines) {
                val start = Point(x0.toDouble(), y0.toDouble())
                drawLine(img, start, Point(x1.toDouble(), y1.toDouble()), color, thickness)
            }
        }
        val graph = PosterCorridors().extract(routesOf(img, mats), emptyList(), img)
        check(checkNotNull(graph.nearestEdge(PixelPoint(920.0, 300.0))).distancePx < 15.0) {
            "unpainted corridor past a rounded corner: ${graph.edges}"
        }
        val points = graph.edges.flatMap { it.polyline }
        check(points.filter { kotlin.math.abs(it.y - 900.0) < 15.0 }.minOf { it.x } < 320.0) {
            "painted hallway stops short of its end wall: ${graph.edges}"
        }
    }

    /** Two traced lines whose paint still joins on the photo, jogging round a symbol. */
    private fun checkColourJoin() = Mats().use { mats ->
        fun line(a: PixelPoint, b: PixelPoint) =
            (0..200).map { PixelPoint(a.x + (b.x - a.x) * it / 200, a.y + (b.y - a.y) * it / 200) }
        for (broken in listOf(false, true)) {
            val routes = mats.own(Mat.zeros(800, 300, CvType.CV_8U))
            val corners = listOf(100.0 to 100.0, 100.0 to 330.0, 130.0 to 420.0, 130.0 to 700.0)
                .map { (x, y) -> Point(x, y) }
            for ((a, b) in corners.zipWithNext()) drawLine(routes, a, b, Scalar(255.0), 9)
            if (broken) mats.own(routes.submat(360, 390, 0, 300)).setTo(Scalar(0.0))
            val nodes = mutableMapOf(
                0 to PixelPoint(100.0, 100.0),
                1 to PixelPoint(100.0, 300.0),
                2 to PixelPoint(130.0, 450.0),
                3 to PixelPoint(130.0, 700.0),
            )
            val paths = mutableListOf(
                CorridorPath(0, 1, line(nodes.getValue(0), nodes.getValue(1))),
                CorridorPath(2, 3, line(nodes.getValue(2), nodes.getValue(3))),
            )
            val empty = ByteArray(800 * 300)
            val runs = IntArray(empty.size)
            val space = FreeSpace(800, 300, empty, empty, 40, runs, runs)
            CorridorLinks(nodes, paths, space)
                .joinByColour(ByteArray(empty.size).also { routes.get(0, 0, it) })
            val count = networks(nodes, paths).second.size
            check(count == if (broken) 2 else 1) { "colour join, broken=$broken: $count" }
        }
    }

    /** Same as tools/test_corridors.py: a patchy band thins to one line, not a ladder. */
    private fun checkLadder() = Mats().use { mats ->
        val routes = mats.own(Mat.zeros(800, 400, CvType.CV_8U))
        for (x in listOf(170.0, 200.0, 230.0)) {
            drawLine(routes, Point(x, 50.0), Point(x, 750.0), Scalar(255.0), 9)
        }
        for (y in 50..750 step 35) {
            drawLine(
                routes,
                Point(170.0, y.toDouble()),
                Point(230.0, y.toDouble()),
                Scalar(255.0),
                9
            )
        }
        val graph = PosterCorridors().extract(routes)
        val points = graph.edges.flatMap { it.polyline }
        check(points.all { kotlin.math.abs(it.x - 200.0) < 15.0 }) { graph.edges }
        check(points.maxOf { it.y } - points.minOf { it.y } > 600.0) { graph.edges }
    }

    /** Same as tools/test_corridors.py: striped pink joins into one network once widened. */
    private fun checkStripedPinkRooms() = Mats().use { mats ->
        val img = walledCorridors(mats)
        val pink = mats.own(Mat(1, 1, CvType.CV_8UC3, Scalar(172.0, 90.0, 210.0)))
        Imgproc.cvtColor(pink, pink, Imgproc.COLOR_HSV2BGR)
        val color = Scalar(pink.get(0, 0))
        for ((x0, x1) in listOf(100 to 250, 250 to 400, 400 to 550, 700 to 850, 850 to 1000)) {
            for ((y0, y1) in listOf(100 to 300, 340 to 520)) {
                for (y in y0 + 4 until y1 - 4 step 6) {
                    val top = Point(x0 + 4.0, y.toDouble())
                    Imgproc.rectangle(img, top, Point(x1 - 5.0, y + 2.0), color, -1)
                }
            }
        }
        val routes = routesOf(img, mats)
        check(inFills(img, routes, mats) > 0.4)
        val graph = PosterCorridors().extract(routes, emptyList(), img)
        check(checkNotNull(graph.nearestEdge(PixelPoint(300.0, 320.0))).distancePx < 15.0)
        check(checkNotNull(graph.nearestEdge(PixelPoint(600.0, 600.0))).distancePx < 15.0)
    }

    private fun checkUnpaintedLink() = Mats().use { mats ->
        val img = paperMat(mats)
        for (y in listOf(650.0, 710.0)) drawLine(img, Point(60.0, y), Point(1140.0, y), WALL, 3)
        for (x in listOf(270.0, 330.0, 870.0, 930.0)) {
            drawLine(img, Point(x, 90.0), Point(x, 650.0), WALL, 3)
        }
        for (y in listOf(90.0, 150.0)) drawLine(img, Point(270.0, y), Point(930.0, y), WALL, 3)
        val route = listOf(
            Point(100.0, 680.0),
            Point(300.0, 680.0),
            Point(300.0, 120.0),
            Point(900.0, 120.0),
            Point(900.0, 680.0),
            Point(1100.0, 680.0),
        )
        route.zipWithNext { a, b -> drawLine(img, a, b, ROUTE, 6) }
        val graph = PosterCorridors().extract(routesOf(img, mats), emptyList(), img)
        check(checkNotNull(graph.nearestEdge(PixelPoint(600.0, 680.0))).distancePx < 15.0) {
            graph.edges
        }
    }

    private fun checkLinkStopsAtDoor() = Mats().use { mats ->
        val img = paperMat(mats)
        for (y in listOf(330.0, 410.0)) drawLine(img, Point(100.0, y), Point(1100.0, y), WALL, 4)
        Imgproc.rectangle(img, Point(400.0, 410.0), Point(800.0, 700.0), WALL, 4)
        drawLine(img, Point(590.0, 410.0), Point(610.0, 410.0), PAPER, 6)
        drawLine(img, Point(560.0, 330.0), Point(560.0, 80.0), WALL, 4)
        drawLine(img, Point(640.0, 330.0), Point(640.0, 80.0), WALL, 4)
        drawLine(img, Point(560.0, 330.0), Point(640.0, 330.0), PAPER, 6)
        drawLine(img, Point(120.0, 370.0), Point(1080.0, 370.0), ROUTE, 6)
        drawLine(img, Point(600.0, 370.0), Point(600.0, 100.0), ROUTE, 6)
        val graph = PosterCorridors().extract(routesOf(img, mats), emptyList(), img)
        check(graph.edges.flatMap { it.polyline }.maxOf { it.y } < 420.0) { graph.edges }
    }

    private fun checkStraightCorridor() = Mats().use { mats ->
        val points = (0..1000).map { PixelPoint(it.toDouble(), 100.0) }
        val start = PixelPoint(10.0, 130.0)
        val line = edgePolyline(start, PixelPoint(1000.0, 100.0), points, 2.0, mats)
        check(line.first() == start)
        check(line.drop(1).all { kotlin.math.abs(it.y - 100.0) < 2.0 }) { line }
    }

    private fun checkDirectoryPoster() {
        val poster = "eh-3-elevators-dir"
        val bitmap = load("$poster.jpg")
        try {
            Mats().use { mats ->
                val bgr = bitmapBgr(bitmap, mats)
                val graph = PosterCorridors().extract(routesOf(bgr, mats), emptyList(), bgr)
                checkGeometry(graph)
                writeGraph("$poster.corridors", graph)
                check(networkCount(graph) == 1) { "$poster: ${networkCount(graph)} networks" }
                check(graph.edges.sumOf { it.lengthPx } > 2000.0)
            }
        } finally {
            bitmap.recycle()
        }
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
                val corridors = PosterCorridors().extract(routes, stars, bgr)
                checkGeometry(corridors)
                check(networkCount(corridors) == 1) { "$poster: split corridors" }
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
                checkGeometry(result.graph)
                writeGraph("$name.photo", result.graph)
                check(networkCount(result.graph) == 1) { "$photo: split corridors" }
                if ("-evac-" in photo) {
                    check(result.warnings.none { "Gold star not found" in it || "Several" in it }) {
                        "$photo: ${result.warnings}"
                    }
                    check(("-blur" in photo) == result.warnings.any { "misread" in it }) { photo }
                }
                report.put("elapsedMs", SystemClock.elapsedRealtime() - start)
                    .put("peakRssKiB", peakRssKiB())
                    .put("corners", JSONArray(quad?.map { JSONArray(listOf(it.x, it.y)) }))
                    .put("sourceSize", JSONArray(listOf(bitmap.width, bitmap.height)))
                    .put("width", result.rectified.width).put("height", result.rectified.height)
                    .put("warnings", JSONArray(result.warnings))
                result.rectified.recycle()
            } catch (failure: PosterRetakeException) {
                check("-crop" in photo) { "$photo: ${failure.message}" }
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

    /**
     * `-e probe <straightened.jpg>`: parses one straightened poster from device storage and
     * writes its graph, OCR words and warnings to files/poster-eval/probe.*, for debugging.
     */
    /**
     * `-e photo <camera.jpg>`: the whole parse from a camera photo, as the app runs it, writing
     * probe.graph.json, probe.warnings.json and the straightened probe.jpg.
     */
    private fun runPhoto(path: String) {
        val result = Bundle()
        try {
            requireOpenCv()
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            val raw = checkNotNull(BitmapFactory.decodeFile(path, options)) { "cannot read $path" }
            val scale = minOf(1f, 3200f / maxOf(raw.width, raw.height))
            val bitmap = android.graphics.Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt(),
                (raw.height * scale).toInt(),
                true,
            )
            val parsed = OfflineMapParser().parse(bitmap)
            writeGraph("probe", parsed.graph)
            File(output, "probe.warnings.json").writeText(JSONArray(parsed.warnings).toString(2))
            File(output, "probe.jpg").outputStream().use {
                parsed.rectified.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
            }
            result.putString("stream", "Photo parsed to ${output.absolutePath}")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", Log.getStackTraceString(failure))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    /** `-e compare <straightened.jpg>`: PP-OCR vs ML Kit room reads and times, to logcat. */
    private fun runCompare(path: String) {
        val result = Bundle()
        try {
            requireOpenCv()
            val bitmap = checkNotNull(BitmapFactory.decodeFile(path)) { "cannot read $path" }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val fast = PosterMlKit.read(bitmap)
            val t1 = android.os.SystemClock.elapsedRealtime()
            val fastAgain = PosterMlKit.read(bitmap)
            val t2 = android.os.SystemClock.elapsedRealtime()
            val slow = Mats().use { mats -> recognizePoster(bitmapBgr(bitmap, mats)) }
            val t3 = android.os.SystemClock.elapsedRealtime()
            val a = distinctRooms(fast).map { it.text }.toSortedSet()
            val b = distinctRooms(slow).map { it.text }.toSortedSet()
            Log.i("MapLensCompare", "mlkit ${t1 - t0} ms (again ${t2 - t1} ms) rooms ${a.size}")
            Log.i("MapLensCompare", "ppocr ${t3 - t2} ms rooms ${b.size}")
            Log.i("MapLensCompare", "only mlkit ${a - b}")
            Log.i("MapLensCompare", "only ppocr ${b - a}")
            Log.i("MapLensCompare", "mlkit exits ${fast.filter { isExitText(it.text) }.size}")
            Log.i("MapLensCompare", "mlkit size ${fastAgain.size}")
            for (r in fast) Log.i("MapLensLines", "${r.text} | ${r.box} | ${r.confidence}")
            result.putString("stream", "compared")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", Log.getStackTraceString(failure))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun runProbe(path: String) {
        val result = Bundle()
        try {
            requireOpenCv()
            val bitmap = checkNotNull(BitmapFactory.decodeFile(path)) { "cannot read $path" }
            Mats().use { mats ->
                val bgr = bitmapBgr(bitmap, mats)
                val parsed = OfflineMapParser().parseRectified(bitmap, bgr)
                writeGraph("probe", parsed.graph)
                File(output, "probe.warnings.json").writeText(
                    JSONArray(parsed.warnings).toString(2),
                )
            }
            result.putString("stream", "Probe written to ${output.absolutePath}")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("stream", Log.getStackTraceString(failure))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
