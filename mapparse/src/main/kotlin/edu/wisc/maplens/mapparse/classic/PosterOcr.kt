package edu.wisc.maplens.mapparse.classic

import android.graphics.Rect
import edu.wisc.maplens.mapparse.ParseProgress
import edu.wisc.maplens.mapparse.ParseStage
import edu.wisc.maplens.mapparse.PosterRetakeException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc

private object OcrParams {
    const val TILE_WIDTH = 1152
    const val TILE_HEIGHT = 864
    const val TILE_OVERLAP = 128
    const val RECOGNIZER_WIDTH = 320
    const val RECOGNIZER_HEIGHT = 48
    const val PIXEL_THRESHOLD = 0.3
    const val BOX_CONFIDENCE = 0.5
    const val TEXT_CONFIDENCE = 0.5f
    const val SUPPLEMENTAL_CONFIDENCE = 0.9f
    const val BOX_EXPANSION = 1.6
    const val SURE_CONFIDENCE = 0.99f
    const val SAME_WORD_OVERLAP = 0.6
    const val VOTE_CONFIDENCE = 0.75f
    val VOTE_MARGINS = listOf(6.0, 10.0, 14.0)
    const val MIN_MODEL_BYTES = 1_000_000
}

/** A clone without `git lfs pull` bundles a small text pointer instead of the model. */
internal fun requireOcrModel(bytes: ByteArray) {
    if (bytes.size < OcrParams.MIN_MODEL_BYTES) {
        throw PosterRetakeException("OCR models missing. Run git lfs pull and rebuild.")
    }
}

/** The label at least two margin re-reads agree on. EH3 rooms all start with 3. */
internal fun marginVote(rereads: List<String>): String? =
    rereads.mapNotNull(::normalizeRoom).filter { it.startsWith("3") }
        .groupingBy { it }.eachCount().entries.firstOrNull { it.value >= 2 }?.key

internal data class OcrCharacter(val text: String, val column: Int, val confidence: Float)

/** CTC removes consecutive repeats, with blank separating repeated digits. */
internal fun decodePosterText(scores: FloatArray, alphabet: List<String>): List<OcrCharacter> {
    require(scores.size % alphabet.size == 0)
    var previous = -1
    return buildList {
        for (column in 0 until scores.size / alphabet.size) {
            val offset = column * alphabet.size
            var best = 0
            for (i in 1 until alphabet.size) {
                if (scores[offset + i] > scores[offset + best]) best = i
            }
            if (best != 0 && best != previous) {
                add(OcrCharacter(alphabet[best], column, scores[offset + best]))
            }
            previous = best
        }
    }
}

/** Overlapping tiles find the same word twice. These boxes are nearly identical. */
internal fun sameWord(a: Array<Point>, b: Array<Point>): Boolean {
    val width = minOf(a.maxOf { it.x }, b.maxOf { it.x }) -
        maxOf(a.minOf { it.x }, b.minOf { it.x })
    val height = minOf(a.maxOf { it.y }, b.maxOf { it.y }) -
        maxOf(a.minOf { it.y }, b.minOf { it.y })
    if (width <= 0 || height <= 0) return false
    fun area(quad: Array<Point>) =
        (quad.maxOf { it.x } - quad.minOf { it.x }) * (quad.maxOf { it.y } - quad.minOf { it.y })
    val shared = width * height
    return shared / (area(a) + area(b) - shared) >= OcrParams.SAME_WORD_OVERLAP
}

internal fun recognizePoster(
    bgr: Mat,
    onProgress: (ParseProgress) -> Unit = {},
): List<PosterReading> = try {
    PosterOcr(onProgress).read(bgr)
} finally {
    // OpenCV's Java Net frees native memory only when finalized. This reclaims about 300 MiB.
    System.gc()
    System.runFinalization()
}

internal fun posterTileOrigins(size: Int, span: Int): List<Int> {
    val last = (size - span).coerceAtLeast(0)
    if (last == 0) return listOf(0)
    val steps = ceil(last.toDouble() / (span - OcrParams.TILE_OVERLAP)).toInt()
    return (0..steps).map { (last.toDouble() * it / steps).roundToInt() }
}

/** Native networks for one read. Reloading costs less than holding them through navigation. */
private class PosterOcr(private val onProgress: (ParseProgress) -> Unit) {
    private val detector = network("det.onnx")
    private val recognizer = network("rec.onnx")
    private val alphabet =
        listOf("") + resource("characters.txt").bufferedReader().use { it.readLines() } + " "

    private fun resource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/poster-ocr/$name")) {
            "Bundled poster OCR asset missing: $name"
        }

    private fun network(name: String): Net = Mats().use { mats ->
        val bytes = resource(name).use { it.readBytes() }
        requireOcrModel(bytes)
        Dnn.readNetFromONNX(mats.own(MatOfByte(*bytes)))
    }

    fun read(bgr: Mat): List<PosterReading> {
        val quads = detect(bgr)
        val sure = mutableListOf<Array<Point>>()
        val primary = quads.flatMapIndexed { index, quad ->
            onProgress(ParseProgress(ParseStage.READ_ROOMS, index, quads.size))
            // Only a certain room read may spare its duplicate. Any other read could improve.
            if (sure.any { sameWord(it, quad) }) return@flatMapIndexed emptyList()
            checked(bgr, quad).also { readings ->
                val reading = readings.singleOrNull()
                if (reading != null && reading.confidence >= OcrParams.SURE_CONFIDENCE &&
                    normalizeRoom(reading.text) != null
                ) {
                    sure += quad
                }
            }
        }
        val rooms = distinctRooms(primary)
        val prose = primary.filter {
            normalizeRoom(it.text) == null && roomRowTokens(it.text).isEmpty() &&
                standaloneRoomTokens(it.text).isEmpty() && it.text.any(Char::isLetter)
        }
        val regions = roomWordRegions(bgr).filter { region ->
            rooms.none { region.contains(it.box.centerX(), it.box.centerY()) } &&
                prose.none { it.box.contains(region.centerX(), region.centerY()) }
        }
        val supplemental = regions.flatMapIndexed { index, region ->
            onProgress(ParseProgress(ParseStage.RECHECK_SMALL, index, regions.size))
            val quad = arrayOf(
                Point(region.left.toDouble(), region.top.toDouble()),
                Point(region.right.toDouble(), region.top.toDouble()),
                Point(region.right.toDouble(), region.bottom.toDouble()),
                Point(region.left.toDouble(), region.bottom.toDouble()),
            )
            recognize(bgr, quad).filter { normalizeRoom(it.text)?.startsWith("3") == true }
                .map { it.copy(possible = it.confidence < OcrParams.SUPPLEMENTAL_CONFIDENCE) }
        }
        return primary + supplemental
    }

    private fun detect(bgr: Mat): List<Array<Point>> = Mats().use { mats ->
        val width = minOf(OcrParams.TILE_WIDTH, bgr.cols())
        val height = minOf(OcrParams.TILE_HEIGHT, bgr.rows())
        val tops = posterTileOrigins(bgr.rows(), height)
        val lefts = posterTileOrigins(bgr.cols(), width)
        var tiles = 0
        buildList {
            for (top in tops) {
                for (left in lefts) {
                    onProgress(
                        ParseProgress(ParseStage.FIND_TEXT, tiles++, tops.size * lefts.size),
                    )
                    val tile = mats.own(bgr.submat(top, top + height, left, left + width))
                    for (quad in detectTile(tile)) {
                        if (quad.any {
                                it.x < 4 || it.y < 4 || it.x > width - 4 || it.y > height - 4
                            }
                        ) {
                            continue
                        }
                        add(quad.map { Point(it.x + left, it.y + top) }.toTypedArray())
                    }
                }
            }
        }
    }

    private fun detectTile(bgr: Mat): List<Array<Point>> = Mats().use { mats ->
        checkCancellation()
        val size = Size(
            (bgr.cols() / 32.0).roundToInt() * 32.0,
            (bgr.rows() / 32.0).roundToInt() * 32.0,
        )
        val input = mats.own(Dnn.blobFromImage(bgr, 1 / 127.5, size, Scalar.all(127.5)))
        detector.setInput(input)
        val output = mats.own(detector.forward())
        val scores = mats.own(output.reshape(1, size.height.toInt()))
        val mask = mats.mat()
        Imgproc.threshold(scores, mask, OcrParams.PIXEL_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
        mask.convertTo(mask, CvType.CV_8U)
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            mask,
            contours,
            mats.mat(),
            Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE
        )
        contours.forEach(mats::own)
        val contour = mats.own(MatOfPoint2f())
        val polygon = mats.own(MatOfPoint())
        val support = mats.own(Mat.zeros(mask.size(), CvType.CV_8U))
        contours.take(1000).mapNotNull { candidate ->
            checkCancellation()
            contour.fromArray(*candidate.toArray())
            val rectangle = Imgproc.minAreaRect(contour)
            val w = rectangle.size.width
            val h = rectangle.size.height
            if (minOf(w, h) < 3.0) return@mapNotNull null
            val corners = Array(4) { Point() }
            rectangle.points(corners)
            polygon.fromArray(*corners)
            support.setTo(Scalar.all(0.0))
            Imgproc.fillPoly(support, listOf(polygon), Scalar.all(255.0))
            val area = Imgproc.boundingRect(polygon)
            val left = area.x.coerceIn(0, mask.cols() - 1)
            val top = area.y.coerceIn(0, mask.rows() - 1)
            val right = (area.x + area.width).coerceIn(left + 1, mask.cols())
            val bottom = (area.y + area.height).coerceIn(top + 1, mask.rows())
            val confidence = Mats().use { local ->
                val values = local.own(scores.submat(top, bottom, left, right))
                val selected = local.own(support.submat(top, bottom, left, right))
                Core.mean(values, selected).`val`[0]
            }
            if (confidence < OcrParams.BOX_CONFIDENCE) return@mapNotNull null
            // The minimum rectangle of a rounded polygon offset grows by this distance on each side.
            val margin = w * h * OcrParams.BOX_EXPANSION / (2 * (w + h))
            val expanded =
                RotatedRect(rectangle.center, Size(w + 2 * margin, h + 2 * margin), rectangle.angle)
            expanded.points(corners)
            val ordered = order(corners).map {
                Point(
                    (it.x * bgr.cols() / size.width).roundToInt().coerceIn(
                        0,
                        bgr.cols() - 1
                    ).toDouble(),
                    (it.y * bgr.rows() / size.height).roundToInt().coerceIn(
                        0,
                        bgr.rows() - 1
                    ).toDouble(),
                )
            }.toTypedArray()
            if (distance(ordered[0], ordered[3]) < 5.0) null else ordered
        }
    }

    /** Tight boxes clip blurred glyphs, so uncertain words are read again with more margin. */
    private fun checked(bgr: Mat, quad: Array<Point>): List<PosterReading> {
        val readings = recognize(bgr, quad)
        val reading = readings.singleOrNull() ?: return readings
        // Voting turned correct suffix rooms such as 3603A into 3603, so a suffix read stands.
        if (reading.confidence >= OcrParams.SURE_CONFIDENCE ||
            reading.text.count(Char::isDigit) < 3 ||
            normalizeRoom(reading.text)?.last()?.isLetter() == true
        ) {
            return readings
        }
        val rereads = mutableListOf<String>()
        for (margin in OcrParams.VOTE_MARGINS) {
            recognize(bgr, widened(quad, margin)).firstOrNull()
                ?.takeIf { it.confidence >= OcrParams.VOTE_CONFIDENCE }?.let { rereads += it.text }
            // Two agreeing reads settle it. A third cannot change the vote.
            marginVote(rereads)?.let { return listOf(reading.copy(text = it)) }
        }
        return readings
    }

    private fun widened(quad: Array<Point>, margin: Double) = arrayOf(
        Point(quad[0].x - margin, quad[0].y - margin),
        Point(quad[1].x + margin, quad[1].y - margin),
        Point(quad[2].x + margin, quad[2].y + margin),
        Point(quad[3].x - margin, quad[3].y + margin),
    )

    private fun order(points: Array<Point>): List<Point> {
        val byX = points.sortedBy { it.x }
        val left = byX.take(2).sortedBy { it.y }
        val right = byX.takeLast(2).sortedBy { it.y }
        return listOf(left[0], right[0], right[1], left[1])
    }

    private fun distance(a: Point, b: Point) = kotlin.math.hypot(a.x - b.x, a.y - b.y)

    private fun recognize(bgr: Mat, quad: Array<Point>): List<PosterReading> = Mats().use { mats ->
        checkCancellation()
        val width = maxOf(distance(quad[0], quad[1]), distance(quad[2], quad[3])).toInt()
        val height = maxOf(distance(quad[0], quad[3]), distance(quad[1], quad[2])).toInt()
        if (width < 3 || height < 3) return emptyList()
        val source = mats.own(MatOfPoint2f(*quad))
        val destination = mats.own(
            MatOfPoint2f(
                Point(0.0, 0.0),
                Point(width.toDouble(), 0.0),
                Point(width.toDouble(), height.toDouble()),
                Point(0.0, height.toDouble()),
            )
        )
        val transform = mats.own(Imgproc.getPerspectiveTransform(source, destination))
        val crop = mats.mat()
        Imgproc.warpPerspective(
            bgr,
            crop,
            transform,
            Size(width.toDouble(), height.toDouble()),
            Imgproc.INTER_CUBIC,
            Core.BORDER_REPLICATE,
        )
        val resizedWidth = ceil(width * OcrParams.RECOGNIZER_HEIGHT.toDouble() / height)
            .toInt().coerceIn(1, OcrParams.RECOGNIZER_WIDTH)
        val resized = mats.mat()
        Imgproc.resize(
            crop,
            resized,
            Size(resizedWidth.toDouble(), OcrParams.RECOGNIZER_HEIGHT.toDouble()),
        )
        resized.convertTo(resized, CvType.CV_32F, 1 / 127.5, -1.0)
        val padded = mats.own(
            Mat.zeros(OcrParams.RECOGNIZER_HEIGHT, OcrParams.RECOGNIZER_WIDTH, CvType.CV_32FC3),
        )
        resized.copyTo(mats.own(padded.colRange(0, resizedWidth)))
        val input = mats.own(Dnn.blobFromImage(padded))
        recognizer.setInput(input)
        val output = mats.own(recognizer.forward())
        check(output.size(2) == alphabet.size)
        val values = FloatArray(output.total().toInt())
        output.get(intArrayOf(0, 0, 0), values)
        val characters = decodePosterText(values, alphabet)
        if (characters.isEmpty()) return emptyList()
        val text = characters.joinToString("") { it.text }
        val confidence = characters.map { it.confidence }.average().toFloat()
        if (confidence < OcrParams.TEXT_CONFIDENCE) return emptyList()
        val box = Rect(
            floor(quad.minOf { it.x }).toInt(),
            floor(quad.minOf { it.y }).toInt(),
            ceil(quad.maxOf { it.x }).toInt(),
            ceil(quad.maxOf { it.y }).toInt(),
        )
        val reading = PosterReading(text, box, confidence)
        val tokens = roomRowTokens(text)
        if (tokens.isEmpty()) return listOf(reading)
        val symbols = characters.filterNot { it.text.isBlank() || it.text in listOf("|", ",") }
        if (symbols.joinToString("") { it.text } != tokens.joinToString("")) return listOf(reading)
        val columnWidth = box.width() * OcrParams.RECOGNIZER_WIDTH.toDouble() /
            resizedWidth / output.size(1)
        val characterWidth = if (symbols.size > 1) {
            (symbols.last().column - symbols.first().column) * columnWidth / (symbols.size - 1)
        } else {
            box.width().toDouble()
        }
        var offset = 0
        listOf(reading) + tokens.map { token ->
            val group = symbols.subList(offset, offset + token.length)
            offset += token.length
            val left = ((group.first().column + 0.5) * columnWidth - characterWidth / 2)
                .roundToInt().coerceIn(0, box.width() - 1)
            val right = ((group.last().column + 0.5) * columnWidth + characterWidth / 2)
                .roundToInt().coerceIn(left + 1, box.width())
            PosterReading(
                token, Rect(box.left + left, box.top, box.left + right, box.bottom),
                minOf(confidence, group.minOf { it.confidence }),
            )
        }
    }
}
