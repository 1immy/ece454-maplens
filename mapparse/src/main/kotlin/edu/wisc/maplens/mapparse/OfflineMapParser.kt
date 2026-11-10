package edu.wisc.maplens.mapparse

import android.graphics.Bitmap
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
        try {
            val unclear = sourceWidth < CLEAR_FRAME_WIDTH || sharpness(flat) < MIN_SHARPNESS
            parseRectified(rectified, flat).let {
                if (unclear) it.copy(warnings = it.warnings + UNCLEAR_PHOTO_WARNING) else it
            }
        } catch (failure: Exception) {
            rectified.recycle()
            throw failure
        }
    }

    internal fun parseRectified(rectified: Bitmap, flat: Mat): ParseResult = Mats().use { mats ->
        checkCancellation()
        onProgress(ParseProgress(ParseStage.CORRIDORS))
        val hsv = mats.mat()
        Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_BGR2HSV)
        val routes = mats.mat()
        routeMask(hsv, routes, mats)
        val stars = detectStars(hsv)
        val extracted = PosterCorridors().extract(routes, stars, flat)
        val corridors = recoverMarkerCorridor(hsv, routes, extracted, stars, flat)
        if (corridors.edges.sumOf { it.lengthPx } < MIN_CORRIDOR_LENGTH) {
            throw PosterRetakeException(
                "No corridors found. Fill the frame with the floor plan and avoid glare.",
            )
        }
        val warnings = mutableListOf("Check the corridors against the poster before saving.")
        val readings = try {
            recognizePoster(flat, onProgress)
        } catch (_: CvException) {
            warnings += "Room recognition failed. Check or enter room labels in review."
            emptyList()
        }
        checkCancellation()
        onProgress(ParseProgress(ParseStage.PLACE_ROOMS))
        val rooms = distinctRooms(readings)
        if (rooms.isEmpty()) {
            warnings += "No room numbers read. Check focus or enter labels in review."
        }
        val labeled = if (rooms.isEmpty()) {
            RoomAttachments(corridors, emptyList())
        } else {
            corridors.attachRooms(
                rooms,
                wallMask(flat, routes, readings),
                flat.cols(),
                warnings,
            )
        }
        val graph = resolveStart(labeled.graph, stars, warnings)
        val possible = distinctRooms(
            readings.filter { it.possible }.map { it.copy(possible = false) },
        ).filter { weak -> rooms.none { it.text == weak.text } }
            .map { RoomPreview(it.text, it.box, null, it.confidence, possible = true) }
        ParseResult(graph, rectified, warnings, labeled.rooms + possible)
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
