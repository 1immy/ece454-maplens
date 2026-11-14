package edu.wisc.maplens.mapparse

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import edu.wisc.maplens.model.PixelPoint
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

// Source pixels across the poster, from downscaled fixtures: readings stay clean at about 900 px,
// false room numbers start near 700 px, and recall falls below 70% with many false rooms near 500 px.
private const val CLEAR_FRAME_WIDTH = 900.0
private const val MIN_FRAME_WIDTH = 500.0

// Laplacian deviation of the warp: the one motion-blurred fixture scores 10.1 and invents rooms.
// Sharp fixtures score at least 12.6. Tuned on those shots, and only a warning.
private const val MIN_SHARPNESS = 11.0
private const val UNCLEAR_PHOTO_WARNING =
    "The photo is small or blurred, so room numbers may be misread. Move closer and retake."

// Fewer sure room reads than this, and the poster may be hung on its side.
private const val FEW_ROOMS = 5

// Every poster with a floor plan yields over 2000 px of corridor.
private const val MIN_CORRIDOR_LENGTH = 1000.0

/** Run on a worker. All post-warp stages use pixels of the returned 2000 px image. */
class OfflineMapParser(
    private val onProgress: (ParseProgress) -> Unit = {},
) : MapParser {
    override fun parse(photo: Bitmap): ParseResult = Mats().use { mats ->
        onProgress(ParseProgress(ParseStage.STRAIGHTEN))
        requireOpenCv()
        checkCancellation()
        val bgr = bitmapBgr(photo, mats)
        val frame = PosterFrame()
        val flat = mats.mat()
        val corners = frame.corners(bgr)
        val sourceWidth = frame.width(corners)
        if (sourceWidth < MIN_FRAME_WIDTH) {
            throw PosterRetakeException(
                "The map is too small in this photo. Move closer so it fills the frame."
            )
        }
        frame.warp(bgr, corners, flat)
        bgr.release()
        val rectified = bitmapFromBgr(flat)
        val unclear = sourceWidth < CLEAR_FRAME_WIDTH || sharpness(flat) < MIN_SHARPNESS
        val first = try {
            parseRectified(rectified, flat)
        } catch (failure: Exception) {
            rectified.recycle()
            throw failure
        }
        // A poster hung on its side (EH3 stair 3542: landscape plan in a portrait frame) has
        // its room numbers running vertically, and the reader only reads level text. When
        // almost nothing was read, find the turn that makes the print level and parse again.
        val upright = if (first.rooms.count { !it.possible } < FEW_ROOMS) {
            uprightTurn(rectified)?.let { rotation ->
                val turned = mats.mat()
                Core.rotate(flat, turned, rotation)
                val image = bitmapFromBgr(turned)
                val again = runCatching { parseRectified(image, turned) }.getOrNull()
                if (again != null && again.rooms.size > first.rooms.size) {
                    first.rectified.recycle()
                    again
                } else {
                    image.recycle()
                    null
                }
            }
        } else {
            null
        }
        val result = upright ?: first
        return@use if (unclear) {
            result.copy(warnings = result.warnings + UNCLEAR_PHOTO_WARNING)
        } else {
            result
        }
    }

    internal fun parseRectified(rectified: Bitmap, flat: Mat): ParseResult = Mats().use { mats ->
        checkCancellation()
        onProgress(ParseProgress(ParseStage.CORRIDORS))
        val timer = StageTimer()
        // Reading the print doesn't depend on the corridors, so it runs alongside them, on its
        // own copy of the image.
        val ocrImage = flat.clone()
        val ocr = Background {
            try {
                onProgress(ParseProgress(ParseStage.FIND_TEXT))
                PosterMlKit.readPoster(rectified, ocrImage)
            } finally {
                ocrImage.release()
            }
        }
        val hsv = mats.mat()
        val routes = mats.mat()
        val stars: List<PixelPoint>
        val corridors = try {
            Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_BGR2HSV)
            routeMask(hsv, routes, mats)
            stars = detectStars(hsv)
            timer.mark("route mask + stars")
            val extracted = PosterCorridors().extract(routes, stars, flat)
            timer.mark("extract")
            recoverMarkerCorridor(hsv, routes, extracted, stars, flat).let { traced ->
                // The trace bends into the gold star: straighten it there before rooms attach.
                (stars.singleOrNull()?.let(traced::straightenedAround) ?: traced).withoutBumps()
            }.also {
                if (it.edges.sumOf { edge -> edge.lengthPx } < MIN_CORRIDOR_LENGTH) {
                    throw PosterRetakeException(
                        "No corridors found. Fill the frame with the floor plan and avoid glare.",
                    )
                }
            }
        } catch (failure: Throwable) {
            ocr.cancel()
            throw failure
        }
        timer.mark("corridors")
        val warnings = mutableListOf("Check the corridors against the poster before saving.")
        val readings = try {
            ocr.await()
        } catch (_: CvException) {
            warnings += "Room recognition failed. Check or enter room labels in review."
            emptyList()
        }
        timer.mark("ocr")
        checkCancellation()
        onProgress(ParseProgress(ParseStage.PLACE_ROOMS))
        // The poster's floor, from its own room numbers (EH posters exist for every floor).
        val floor = posterFloor(readings)
        val rooms = distinctRooms(readings, floor)
        if (rooms.isEmpty()) {
            warnings += "No room numbers read. Check focus or enter labels in review."
        }
        val labeled = if (rooms.isEmpty()) {
            RoomAttachments(corridors, emptyList())
        } else {
            val walls = wallMask(flat, routes, readings)
            val ink = doorInk(flat)
            timer.mark("wall mask")
            corridors.attachRooms(rooms, walls, flat.cols(), warnings, ink)
        }
        timer.mark("attach rooms")
        val features = exitSigns(readings) + elevatorIcons(hsv) + aedIcons(hsv)
        timer.mark("features")
        val graph = resolveStart(labeled.graph, stars, warnings).attachFeatures(features)
            .let { if (floor != null) it.copy(floor = floor.toString()) else it }
        timer.mark("rooms+features")
        val possible = distinctRooms(
            readings.filter { it.possible }.map { it.copy(possible = false) },
            floor,
        ).filter { weak -> rooms.none { it.text == weak.text } }
            .map { RoomPreview(it.text, it.box, null, it.confidence, possible = true) }
        ParseResult(graph, rectified, warnings, labeled.rooms + possible)
    }
}

/** Logs how long each parse stage took, for tuning parse time on a phone. */
internal class StageTimer {
    private var last = SystemClock.elapsedRealtime()

    fun mark(stage: String) {
        val now = SystemClock.elapsedRealtime()
        Log.i("MapLensParse", "$stage ${now - last} ms")
        last = now
    }
}

private fun sharpness(bgr: Mat): Double = Mats().use { mats ->
    val gray = mats.mat()
    val edges = mats.mat()
    Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
    Imgproc.Laplacian(gray, edges, CvType.CV_64F)
    val deviation = mats.own(MatOfDouble())
    Core.meanStdDev(edges, mats.own(MatOfDouble()), deviation)
    deviation.get(0, 0)[0]
}

internal fun requireOpenCv() {
    if (!OpenCVLoader.initLocal()) {
        throw PosterRetakeException(
            "Map processing could not start. Restart the app and try again."
        )
    }
}

internal fun bitmapBgr(bitmap: Bitmap, mats: Mats): Mat {
    val bgr = mats.mat()
    Utils.bitmapToMat(bitmap, bgr)
    Imgproc.cvtColor(bgr, bgr, Imgproc.COLOR_RGBA2BGR)
    return bgr
}

internal fun bitmapFromBgr(bgr: Mat): Bitmap = Mats().use { mats ->
    val rgba = mats.mat()
    Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
    Bitmap.createBitmap(bgr.cols(), bgr.rows(), Bitmap.Config.ARGB_8888).also {
        Utils.matToBitmap(rgba, it)
    }
}
