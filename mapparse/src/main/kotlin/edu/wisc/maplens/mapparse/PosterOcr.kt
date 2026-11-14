package edu.wisc.maplens.mapparse

import android.graphics.Rect
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

/**
 * The label at least two margin re-reads agree on, on [floor] when it is known. Reads happen
 * before the poster's floor is, so the parser passes null and drops other floors later.
 */
internal fun marginVote(rereads: List<String>, floor: Char? = null): String? =
    rereads.mapNotNull(::normalizeRoom).filter { floor == null || it.first() == floor }
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

/**
 * Which turn (a Core.ROTATE_* code) makes the poster's print level, or null if it already is
 * or none reads clearly better. Reads one tile from the middle in each orientation, so it
 * costs a few seconds and only runs when a parse found almost no rooms.
 */
internal fun uprightRotation(bgr: Mat): Int? = try {
    val ocr = PosterOcr {}
    Mats().use { mats ->
        val w = minOf(OcrParams.TILE_WIDTH, bgr.cols(), bgr.rows())
        val left = (bgr.cols() - w) / 2
        val top = (bgr.rows() - w) / 2
        val tile = mats.own(bgr.submat(top, top + w, left, left + w).clone())
        val level = ocr.roomsIn(tile)
        listOf(Core.ROTATE_90_CLOCKWISE, Core.ROTATE_180, Core.ROTATE_90_COUNTERCLOCKWISE)
            .map { code ->
                val turned = mats.mat()
                Core.rotate(tile, turned, code)
                code to ocr.roomsIn(turned)
            }
            .maxByOrNull { it.second }
            ?.takeIf { (_, rooms) -> rooms >= MIN_UPRIGHT_ROOMS && rooms > 2 * level }
            ?.first
    }
} finally {
    System.gc()
    System.runFinalization()
}

/**
 * Whether the print in the middle of the poster runs vertically: most text boxes the detector
 * finds there are tall rather than wide. One detector pass on one tile, about a second.
 */
internal fun looksSideways(bgr: Mat): Boolean = try {
    val ocr = PosterOcr {}
    Mats().use { mats ->
        val w = minOf(OcrParams.TILE_WIDTH, bgr.cols(), bgr.rows())
        val left = (bgr.cols() - w) / 2
        val top = (bgr.rows() - w) / 2
        val tile = mats.own(bgr.submat(top, top + w, left, left + w).clone())
        val shapes = ocr.boxShapes(tile)
        shapes.size >= MIN_SHAPE_BOXES && shapes.count { it > TALL_RATIO } > shapes.size / 2
    }
} finally {
    System.gc()
    System.runFinalization()
}

private const val MIN_SHAPE_BOXES = 6
private const val TALL_RATIO = 1.3

/** Room reads needed in the test tile to trust a turn. */
private const val MIN_UPRIGHT_ROOMS = 3

/**
 * PP-OCR's recognizer alone on word-sized [regions] (no detector pass): the second look at
 * patches ML Kit left unread. Readings below the supplemental confidence come back possible.
 */
internal fun recheckRegions(bgr: Mat, regions: List<Rect>): List<PosterReading> =
    recheckEach(bgr, regions).flatten()

/** [recheckRegions] keeping each region's reads apart, with the networks loaded once. */
internal fun recheckEach(bgr: Mat, regions: List<Rect>): List<List<PosterReading>> {
    if (regions.isEmpty()) return emptyList()
    // A few recognizers side by side, each on its own thread and slice of the regions: each
    // read is small, so they don't compete much. No more copies than regions: loading one
    // costs about as much as four reads.
    val slices = regions.indices.groupBy { it % minOf(RECHECK_THREADS, regions.size) }
        .values.toList()
    val bySlice = slices.parallelMap { slice ->
        Recognizers.use { ocr ->
            fun quad(i: Int) = regions[i].let { region ->
                arrayOf(
                    Point(region.left.toDouble(), region.top.toDouble()),
                    Point(region.right.toDouble(), region.top.toDouble()),
                    Point(region.right.toDouble(), region.bottom.toDouble()),
                    Point(region.left.toDouble(), region.bottom.toDouble()),
                )
            }
            fun marked(reads: List<PosterReading>) =
                reads.map { it.copy(possible = it.confidence < OcrParams.SUPPLEMENTAL_CONFIDENCE) }
            // Two words per pass where they fit side by side; a long one is read alone.
            slice.chunked(2).flatMap { pair ->
                val both = pair.takeIf { it.size == 2 }
                    ?.let { (a, b) -> ocr.readPair(bgr, quad(a), quad(b)) }
                if (both != null) {
                    listOf(pair[0] to marked(both.first), pair[1] to marked(both.second))
                } else {
                    pair.map { i -> i to marked(ocr.readQuad(bgr, quad(i))) }
                }
            }
        }
    }
    val reads = bySlice.flatten().toMap()
    return regions.indices.map { reads.getValue(it) }
}

/**
 * Recognizers loaded during one parse, lent out by [recheckEach] and kept until [release], so
 * a later pass (the cut-number reread) doesn't load the 21 MB network again.
 */
internal object Recognizers {
    private val idle = java.util.concurrent.ConcurrentLinkedQueue<PosterOcr>()

    fun <T> use(block: (PosterOcr) -> T): T {
        val ocr = idle.poll() ?: PosterOcr {}
        try {
            return block(ocr)
        } finally {
            idle.add(ocr)
        }
    }

    /** Frees the networks at the end of a parse: holding them through navigation costs more. */
    fun release() {
        idle.clear()
        System.gc()
        System.runFinalization()
    }
}

/** Recognizer copies run at once by [recheckEach]. */
private const val RECHECK_THREADS = 2

internal fun posterTileOrigins(size: Int, span: Int): List<Int> {
    val last = (size - span).coerceAtLeast(0)
    if (last == 0) return listOf(0)
    val steps = ceil(last.toDouble() / (span - OcrParams.TILE_OVERLAP)).toInt()
    return (0..steps).map { (last.toDouble() * it / steps).roundToInt() }
}

/** Native networks for one read. Reloading costs less than holding them through navigation. */
internal class PosterOcr(private val onProgress: (ParseProgress) -> Unit) {
    private val detector by lazy { network("det.onnx") }
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

    /** One recognizer read of [quad], for [recheckRegions]. */
    fun readQuad(bgr: Mat, quad: Array<Point>): List<PosterReading> = recognize(bgr, quad)

    /** Height over width of each text box the detector finds in [tile]. */
    fun boxShapes(tile: Mat): List<Double> = detectTile(tile).map { quad ->
        val w = maxOf(distance(quad[0], quad[1]), distance(quad[2], quad[3]))
        val h = maxOf(distance(quad[0], quad[3]), distance(quad[1], quad[2]))
        h / maxOf(w, 1.0)
    }

    /** Room numbers read in one tile-sized image: the orientation test. */
    fun roomsIn(tile: Mat): Int = detectTile(tile).count { quad ->
        recognize(tile, quad).any { normalizeRoom(it.text) != null }
    }

    fun read(bgr: Mat): List<PosterReading> {
        val timer = StageTimer()
        val quads = detect(bgr)
        timer.mark("ocr detect (${quads.size} boxes)")
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
        timer.mark("ocr read")
        val floor = posterFloor(primary)
        val rooms = distinctRooms(primary, floor)
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
            recognize(bgr, quad).filter { reading ->
                normalizeRoom(reading.text)?.let { floor == null || it.first() == floor } == true
            }
                .map { it.copy(possible = it.confidence < OcrParams.SUPPLEMENTAL_CONFIDENCE) }
        }
        timer.mark("ocr recheck (${regions.size} regions)")
        // Exit signs are white on red, which the text detector often doesn't box (EH3, Nov 13:
        // three of seven, including the one beside START). Red patches the size of a sign are
        // read directly; those that say EXIT join the reads.
        val found = (primary + supplemental).filter { isExitText(it.text) }
        val signs = exitPatches(bgr)
            .filter { patch -> found.none { patch.contains(it.box.centerX(), it.box.centerY()) } }
            .mapNotNull { patch ->
                // Two crops: a reading that is borderline in one is usually clean in the other.
                val reads = listOf(0.0, 6.0).flatMap { pad ->
                    val quad = arrayOf(
                        Point(patch.left - pad, patch.top - pad),
                        Point(patch.right + pad, patch.top - pad),
                        Point(patch.right + pad, patch.bottom + pad),
                        Point(patch.left - pad, patch.bottom + pad),
                    )
                    recognize(bgr, quad)
                }
                android.util.Log.i("MapLensParse", "red patch $patch: ${reads.map { it.text }}")
                // A red sign-shaped patch is already most of the evidence: a near miss counts.
                reads.firstOrNull { isExitLike(it.text) }?.copy(text = "EXIT", box = patch)
            }
        timer.mark("exit signs (${signs.size})")
        return primary + supplemental + signs
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
        val crop = prepared(bgr, quad, mats) ?: return emptyList()
        val input = mats.own(
            Mat.zeros(OcrParams.RECOGNIZER_HEIGHT, OcrParams.RECOGNIZER_WIDTH, CvType.CV_32FC3),
        )
        crop.copyTo(mats.own(input.colRange(0, crop.cols())))
        val (characters, columns) = run(input, mats)
        readingsFrom(characters, boundsOf(quad), crop.cols(), columns)
    }

    /**
     * Two words read in one pass of the recognizer: side by side in its 320 px input with a
     * blank gap, the characters split back by where they sit. The network's cost is per input,
     * not per word, so a pair costs what one word did. Null when the two don't fit together.
     */
    fun readPair(
        bgr: Mat,
        a: Array<Point>,
        b: Array<Point>,
    ): Pair<List<PosterReading>, List<PosterReading>>? = Mats().use { mats ->
        checkCancellation()
        val first = prepared(bgr, a, mats) ?: return null
        val second = prepared(bgr, b, mats) ?: return null
        // The second word starts on a column boundary, so its characters' columns shift whole.
        val start = (first.cols() + PAIR_GAP + COLUMN_PX - 1) / COLUMN_PX * COLUMN_PX
        if (start + second.cols() > OcrParams.RECOGNIZER_WIDTH) return null
        val input = mats.own(
            Mat.zeros(OcrParams.RECOGNIZER_HEIGHT, OcrParams.RECOGNIZER_WIDTH, CvType.CV_32FC3),
        )
        first.copyTo(mats.own(input.colRange(0, first.cols())))
        second.copyTo(mats.own(input.colRange(start, start + second.cols())))
        val (characters, columns) = run(input, mats)
        val step = OcrParams.RECOGNIZER_WIDTH.toDouble() / columns
        if (step != COLUMN_PX.toDouble()) return null
        val split = (first.cols() + start) / 2.0
        val (left, right) = characters.partition { (it.column + 0.5) * step < split }
        val shift = start / COLUMN_PX
        readingsFrom(left, boundsOf(a), first.cols(), columns) to
            readingsFrom(
                right.map { it.copy(column = it.column - shift) },
                boundsOf(b),
                second.cols(),
                columns,
            )
    }

    /** [quad] straightened to the recognizer's height and normalised; null if too small. */
    private fun prepared(bgr: Mat, quad: Array<Point>, mats: Mats): Mat? {
        val width = maxOf(distance(quad[0], quad[1]), distance(quad[2], quad[3])).toInt()
        val height = maxOf(distance(quad[0], quad[3]), distance(quad[1], quad[2])).toInt()
        if (width < 3 || height < 3) return null
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
        return resized
    }

    /** One pass of the recognizer over a full-size [input]: characters and output columns. */
    private fun run(input: Mat, mats: Mats): Pair<List<OcrCharacter>, Int> {
        recognizer.setInput(mats.own(Dnn.blobFromImage(input)))
        val output = mats.own(recognizer.forward())
        check(output.size(2) == alphabet.size)
        val values = FloatArray(output.total().toInt())
        output.get(intArrayOf(0, 0, 0), values)
        return decodePosterText(values, alphabet) to output.size(1)
    }

    private fun boundsOf(quad: Array<Point>) = Rect(
        floor(quad.minOf { it.x }).toInt(),
        floor(quad.minOf { it.y }).toInt(),
        ceil(quad.maxOf { it.x }).toInt(),
        ceil(quad.maxOf { it.y }).toInt(),
    )

    /**
     * The reads in one word's [characters] ([resizedWidth] px of the input, [columns] output
     * columns across the full input): the word, plus each room number of a run of them.
     */
    private fun readingsFrom(
        characters: List<OcrCharacter>,
        box: Rect,
        resizedWidth: Int,
        columns: Int,
    ): List<PosterReading> {
        if (characters.isEmpty()) return emptyList()
        val text = characters.joinToString("") { it.text }
        val confidence = characters.map { it.confidence }.average().toFloat()
        if (confidence < OcrParams.TEXT_CONFIDENCE) return emptyList()
        val reading = PosterReading(text, box, confidence)
        val tokens = roomRowTokens(text)
        if (tokens.isEmpty()) return listOf(reading)
        val symbols = characters.filterNot { it.text.isBlank() || it.text in listOf("|", ",") }
        if (symbols.joinToString("") { it.text } != tokens.joinToString("")) return listOf(reading)
        val columnWidth = box.width() * OcrParams.RECOGNIZER_WIDTH.toDouble() /
            resizedWidth / columns
        val characterWidth = if (symbols.size > 1) {
            (symbols.last().column - symbols.first().column) * columnWidth / (symbols.size - 1)
        } else {
            box.width().toDouble()
        }
        var offset = 0
        return listOf(reading) + tokens.map { token ->
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

    private companion object {
        /** Blank between a pair's two words, so no character runs across. */
        const val PAIR_GAP = 24

        /** Input pixels per output column of the recognizer (320 px in, 40 columns out). */
        const val COLUMN_PX = 8
    }
}

/** Whether a read is an exit sign's word: "EXIT", maybe with "To" or a room run into it. */
internal fun isExitText(text: String): Boolean {
    val letters = text.uppercase(java.util.Locale.ROOT).filter(Char::isLetter)
    return "EXIT" in letters && letters.length <= 7
}

/**
 * Red patches about the size of an exit sign (white EXIT on red, 60–90 × 25–45 px on a
 * 2000 px poster), letters closed in. Neighbouring red marks can merge in, so the bounds are
 * generous; reading the patch decides.
 */
internal fun exitPatches(bgr: Mat): List<Rect> = Mats().use { mats ->
    val hsv = mats.mat()
    Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
    val red = mats.mat()
    val wrap = mats.mat()
    Core.inRange(hsv, Scalar(0.0, 85.0, 40.0), Scalar(9.0, 255.0, 255.0), red)
    Core.inRange(hsv, Scalar(172.0, 85.0, 40.0), Scalar(180.0, 255.0, 255.0), wrap)
    Core.bitwise_or(red, wrap, red)
    Imgproc.morphologyEx(red, red, Imgproc.MORPH_OPEN, mats.kernel(3, 3))
    // Thick red only: letters filled sideways, then anything thinner than a sign dropped. A
    // sign that touches the red-brown route line (EH3, beside the AED) stays a sign-sized band
    // here, where the closed blobs below join it to the whole line.
    val band = mats.mat()
    Imgproc.morphologyEx(red, band, Imgproc.MORPH_CLOSE, mats.kernel(15, 5))
    Imgproc.morphologyEx(band, band, Imgproc.MORPH_OPEN, mats.kernel(25, 11))
    val thick = components(band, mats).filter { r ->
        r.width() in 35..200 && r.height() in 14..70 && r.width() >= 1.3 * r.height()
    }.map { r ->
        Rect(
            (r.left - 4).coerceAtLeast(0),
            (r.top - 4).coerceAtLeast(0),
            (r.right + 4).coerceAtMost(bgr.cols()),
            (r.bottom + 4).coerceAtMost(bgr.rows()),
        )
    }
    Imgproc.morphologyEx(red, red, Imgproc.MORPH_CLOSE, mats.kernel(9, 9))
    val labels = mats.mat()
    val stats = mats.mat()
    val count = Imgproc.connectedComponentsWithStats(
        red,
        labels,
        stats,
        mats.mat(),
        8,
        CvType.CV_32S
    )
    val trimmed = (1 until count).mapNotNull { i ->
        val row = IntArray(5)
        stats.get(i, 0, row)
        val (x, y, w, h) = row
        if (w !in 40..260 || h !in 16..110 || w < 1.2 * h) return@mapNotNull null
        val sign = signBand(red, x, y, w, h)
        val pad = 4
        Rect(
            (sign.left - pad).coerceAtLeast(0),
            (sign.top - pad).coerceAtLeast(0),
            (sign.right + pad).coerceAtMost(bgr.cols()),
            (sign.bottom + pad).coerceAtMost(bgr.rows()),
        )
    }
    // Both ways in, one patch per sign.
    (trimmed + thick).fold(mutableListOf<Rect>()) { kept, r ->
        val near = kept.any {
            kotlin.math.hypot(
                (it.exactCenterX() - r.exactCenterX()).toDouble(),
                (it.exactCenterY() - r.exactCenterY()).toDouble(),
            ) < SAME_SIGN_PX
        }
        if (!near) kept += r
        kept
    }
}

/** Bounding boxes of the 8-connected blobs in [mask]. */
private fun components(mask: Mat, mats: Mats): List<Rect> {
    val stats = mats.mat()
    val count = Imgproc.connectedComponentsWithStats(
        mask,
        mats.mat(),
        stats,
        mats.mat(),
        8,
        CvType.CV_32S,
    )
    return (1 until count).map { i ->
        val row = IntArray(5)
        stats.get(i, 0, row)
        Rect(row[0], row[1], row[0] + row[2], row[1] + row[3])
    }
}

private const val SAME_SIGN_PX = 20.0

/**
 * The sign itself within a red patch: the longest run of rows that are mostly red, then the
 * columns mostly red within it. A warning triangle or extinguisher dot merged into the patch
 * squashed the crop until only "EX" was read (EH3 stair 3424 sign, Nov 13).
 */
private fun signBand(red: Mat, x: Int, y: Int, w: Int, h: Int): Rect {
    val pixels = ByteArray(w * h)
    red.submat(y, y + h, x, x + w).let { patch ->
        patch.get(0, 0, pixels)
        patch.release()
    }
    fun on(col: Int, row: Int) = pixels[row * w + col] != 0.toByte()
    val rowFull = (0 until h).map { r -> (0 until w).count { on(it, r) } >= SIGN_ROW_SHARE * w }
    var best = 0 until 0
    var start = -1
    for (r in 0..h) {
        val full = r < h && rowFull[r]
        if (full && start < 0) start = r
        if (!full && start >= 0) {
            if (r - start > best.last - best.first + 1) best = start until r
            start = -1
        }
    }
    if (best.isEmpty() || best.last - best.first + 1 < MIN_SIGN_ROWS) {
        return Rect(
            x,
            y,
            x + w,
            y + h
        )
    }
    val rows = best.last - best.first + 1
    val cols = (0 until w).filter { c -> best.count { on(c, it) } >= SIGN_COL_SHARE * rows }
    val left = cols.firstOrNull() ?: 0
    val right = (cols.lastOrNull() ?: (w - 1)) + 1
    return Rect(x + left, y + best.first, x + right, y + best.last + 1)
}

private const val SIGN_ROW_SHARE = 0.5
private const val SIGN_COL_SHARE = 0.5
private const val MIN_SIGN_ROWS = 12

/**
 * A loose "EXIT" for reads of red sign patches: 1, l and | read as I, 0 as O, and one letter
 * off still counts ("EXlT", "FXIT", "EXT"). Only used where the colour and shape already say
 * exit sign; [isExitText] stays strict for words found anywhere on the poster.
 */
internal fun isExitLike(text: String): Boolean {
    val letters = text.uppercase(java.util.Locale.ROOT).map {
        when (it) {
            '1', 'L', '|', '!' -> 'I'
            '0' -> 'O'
            else -> it
        }
    }.filter(Char::isLetter).joinToString("")
    // The front of the word survives a tight crop: "EX" from a red sign is the sign.
    if (letters.startsWith("EX") && letters.length <= 8) return true
    if (letters.length !in 3..8) return false
    return (0..letters.length).any { start ->
        (3..5).any { len ->
            start + len <= letters.length &&
                editDistance(letters.substring(start, start + len), "EXIT") <= 1
        }
    }
}

private fun editDistance(a: String, b: String): Int {
    val row = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var diagonal = row[0]
        row[0] = i
        for (j in 1..b.length) {
            val above = row[j]
            val swap = diagonal + if (a[i - 1] == b[j - 1]) 0 else 1
            row[j] = minOf(row[j] + 1, row[j - 1] + 1, swap)
            diagonal = above
        }
    }
    return row[b.length]
}
