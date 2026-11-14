package edu.wisc.maplens.mapparse

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Poster text with ML Kit's on-device recognizer (the bundled Latin model: it ships inside the
 * APK, so it works offline with no Play services download). It reads a whole 2000 px poster in
 * well under a second on a Pixel 7a, where the PP-OCR networks took 12-15 s.
 *
 * Its lines are turned into [PosterReading]s the way the rest of the parser expects:
 * - a line that is one room number ("3540B") is one reading;
 * - a row of room numbers ("3442 3440 3438") gives one reading per room, each with its own box;
 * - anything else (the AED legend, street names, "EXIT") stays one reading of the whole line,
 *   so the legend's room list is never taken for rooms on the plan.
 *
 * Call off the main thread: it blocks on ML Kit's task.
 */
internal object PosterMlKit {
    private val recognizer by lazy {
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }

    /**
     * Everything the parser reads on [poster] ([bgr] is the same image for OpenCV): one pass
     * over the whole poster, then one pass over a mosaic of the small word patches the first
     * missed and the red exit-sign patches, enlarged. Two ML Kit calls in all.
     */
    fun readPoster(poster: Bitmap, bgr: Mat): List<PosterReading> {
        val timer = StageTimer()
        val first = read(poster)
        timer.mark("mlkit")
        val floor = posterFloor(first)
        val rooms = distinctRooms(first, floor)
        // Legend lines ("2341, 3040, B222, B335") are lists, never labels on the plan.
        val prose = first.filter {
            normalizeRoom(it.text) == null &&
                (it.text.count(Char::isLetter) >= 3 || ',' in it.text)
        }
        val words = roomWordRegions(bgr).filter { region ->
            rooms.none { region.contains(it.box.centerX(), it.box.centerY()) } &&
                prose.none { it.box.contains(region.centerX(), region.centerY()) }
        }
        val exits = exitPatches(bgr).filter { patch ->
            first.none { isExitText(it.text) && patch.contains(it.box.centerX(), it.box.centerY()) }
        }
        val cells = words.map { it to false } + exits.map { it to true }
        timer.mark("regions (${words.size} words, ${exits.size} signs)")
        val second = mosaicRead(bgr, cells.map { it.first }, cells.map { it.second })
        timer.mark("mosaic")
        val small = second.filter { (cell, _) -> !cells[cell].second }.map { it.second }
            .filter { reading ->
                normalizeRoom(reading.text)?.let { floor == null || it.first() == floor } == true
            }
            .map { it.copy(possible = it.confidence < SMALL_SURE) }
        // What ML Kit still left unread, the slower but sharper PP-OCR recognizer reads: only
        // those few patches, and alongside corridor extraction, so it adds no waiting.
        val readNow = rooms + small
        val stillUnread = words.filter { region ->
            readNow.none { region.contains(it.box.centerX(), it.box.centerY()) }
        }
        val signs = second
            .filter { (cell, reading) -> cells[cell].second && isExitLike(reading.text) }
            .map { (cell, reading) -> reading.copy(text = "EXIT", box = Rect(cells[cell].first)) }
            .distinctBy { it.box }
        // Red patches ML Kit could not read as EXIT get two crops each, in the same pass: the
        // recognizer network is loaded once for everything left (it was once per sign, 6.7 s).
        val unreadSigns = exits.filter { patch -> signs.none { it.box == patch } }
        // One crop per sign: patches are already trimmed to the sign's own red band.
        val signCrops = unreadSigns.map { padded(it, SIGN_PAD) }
        // ML Kit's confidence runs lower than PP-OCR's for the same right answer (0.54-0.97
        // on a scan where almost every room was right), so a low one gets a second opinion:
        // the sharper recognizer reads the same spot, and agreement makes the room sure.
        val doubtful = (rooms + small).filter { it.confidence < MLKIT_SURE }
        val rechecked = recheckEach(
            bgr,
            stillUnread.take(MAX_RECHECK) + signCrops + doubtful.map { padded(it.box, DOUBT_PAD) },
        )
        timer.mark(
            "recheck ${stillUnread.size} words, ${unreadSigns.size} signs, ${doubtful.size} doubts",
        )
        val opinions = rechecked.takeLast(doubtful.size)
        val agreed = doubtful.zip(opinions).filter { (read, again) ->
            again.any { normalizeRoom(it.text) == normalizeRoom(read.text) }
        }.map { it.first.box }.toSet()
        val sharp = rechecked.take(
            minOf(stillUnread.size, MAX_RECHECK)
        ).flatten().filter { reading ->
            normalizeRoom(reading.text)?.let { floor == null || it.first() == floor } == true
        }
        val sharpSigns = unreadSigns.zip(
            rechecked.drop(minOf(stillUnread.size, MAX_RECHECK)).take(signCrops.size).chunked(1),
        )
            .mapNotNull { (patch, reads) ->
                reads.flatten().firstOrNull { isExitLike(it.text) }
                    ?.copy(text = "EXIT", box = Rect(patch), possible = false)
            }
        // Room reads on the review's scale (sure at 0.97+): sure when ML Kit was confident or
        // the second reader agreed; otherwise left as read, so the review asks for a look.
        val calibrated = (first + small).map { read ->
            val room = normalizeRoom(read.text) != null
            when {
                !room -> read
                read.confidence >= MLKIT_SURE || read.box in agreed ->
                    read.copy(confidence = SURE, possible = false)
                else -> read
            }
        }
        // Rooms are numbered in runs: a doubtful number that fits its neighbours' run (their
        // first three digits) is most likely right; one that fits none stays for a look.
        val placed = (calibrated + sharp).filter { normalizeRoom(it.text) != null }
        val trended = calibrated.map { read ->
            val label = normalizeRoom(read.text)
            if (label == null || read.confidence >= SURE || read.possible) return@map read
            if (fitsNeighbours(label, read, placed)) read.copy(confidence = SURE) else read
        }
        return trended + sharp + signs + sharpSigns
    }

    /**
     * Reads [regions] of the poster [bgr] in one ML Kit call: each is cut out, enlarged to a
     * readable height, made clean black-on-white (Otsu threshold; [light] text such as an exit
     * sign's white-on-red is flipped) and laid out with wide gaps on one white sheet. Returns
     * each reading with the index of the region it came from, its box mapped back onto the
     * poster.
     */
    private fun mosaicRead(
        bgr: Mat,
        regions: List<Rect>,
        light: List<Boolean>,
    ): List<Pair<Int, PosterReading>> {
        if (regions.isEmpty()) return emptyList()
        data class Cell(val index: Int, val source: Rect, val at: Rect, val scale: Double)
        val cells = mutableListOf<Cell>()
        var x = GAP
        var y = GAP
        var rowHeight = 0
        for ((i, r) in regions.withIndex()) {
            val scale = (CELL_HEIGHT.toDouble() / r.height()).coerceIn(1.0, MAX_SCALE)
            val w = (r.width() * scale).toInt()
            val h = (r.height() * scale).toInt()
            if (x + w + GAP > SHEET_WIDTH) {
                x = GAP
                y += rowHeight + GAP
                rowHeight = 0
            }
            cells += Cell(i, r, Rect(x, y, x + w, y + h), scale)
            x += w + GAP
            rowHeight = maxOf(rowHeight, h)
        }
        val sheet = Mats().use { mats ->
            val gray = mats.mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val page = mats.own(
                Mat(
                    y + rowHeight + GAP,
                    SHEET_WIDTH,
                    CvType.CV_8UC1,
                    Scalar(255.0),
                ),
            )
            for (c in cells) {
                val src = c.source
                val left = src.left.coerceAtLeast(0)
                val top = src.top.coerceAtLeast(0)
                val right = src.right.coerceAtMost(gray.cols())
                val bottom = src.bottom.coerceAtMost(gray.rows())
                if (right <= left || bottom <= top) continue
                val crop = mats.own(gray.submat(top, bottom, left, right))
                val big = mats.mat()
                Imgproc.resize(
                    crop,
                    big,
                    Size(c.at.width().toDouble(), c.at.height().toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_CUBIC,
                )
                Imgproc.threshold(
                    big,
                    big,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU,
                )
                if (light[c.index]) Core.bitwise_not(big, big)
                big.copyTo(mats.own(page.submat(c.at.top, c.at.bottom, c.at.left, c.at.right)))
            }
            val rgba = mats.mat()
            Imgproc.cvtColor(page, rgba, Imgproc.COLOR_GRAY2RGBA)
            Bitmap.createBitmap(page.cols(), page.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(rgba, it)
            }
        }
        try {
            return read(sheet).mapNotNull { reading ->
                val cx = reading.box.centerX()
                val cy = reading.box.centerY()
                val cell = cells.firstOrNull { it.at.contains(cx, cy) } ?: return@mapNotNull null
                fun back(v: Int, from: Int, to: Int) = to + ((v - from) / cell.scale).toInt()
                val box = Rect(
                    back(reading.box.left, cell.at.left, cell.source.left)
                        .coerceIn(cell.source.left, cell.source.right - 1),
                    cell.source.top,
                    back(reading.box.right, cell.at.left, cell.source.left)
                        .coerceIn(cell.source.left + 1, cell.source.right),
                    cell.source.bottom,
                )
                cell.index to reading.copy(box = box)
            }
        } finally {
            sheet.recycle()
        }
    }

    fun read(poster: Bitmap): List<PosterReading> {
        checkCancellation()
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(poster, 0)))
        return text.textBlocks.flatMap { block -> block.lines.flatMap(::readings) }
    }

    /**
     * Reads [poster] turned by [degrees] (90, 180 or 270) and maps every box back onto the
     * unturned poster: used to tell which way up a poster hangs.
     */
    fun roomsTurned(poster: Bitmap, degrees: Int): Int {
        val turned = Bitmap.createBitmap(
            poster,
            0,
            0,
            poster.width,
            poster.height,
            Matrix().apply { postRotate(degrees.toFloat()) },
            true,
        )
        return try {
            read(turned).count { normalizeRoom(it.text) != null }
        } finally {
            turned.recycle()
        }
    }

    private fun readings(line: Text.Line): List<PosterReading> {
        val box = line.boundingBox ?: return emptyList()
        val confidence = line.confidence.orSure()
        val whole = PosterReading(line.text, Rect(box), confidence)
        if (normalizeRoom(line.text) != null) return listOf(whole)
        val words = line.elements.filter { it.boundingBox != null }
        // Prose (the AED legend, street names) stays whole: a word of three letters or a
        // comma-separated list means the numbers in it are not labels on the plan.
        val prose = words.any { w -> w.text.count(Char::isLetter) >= 3 || w.text.endsWith(",") }
        if (prose) return listOf(whole)
        val rooms = words.flatMap { w ->
            roomTokens(w.text, Rect(w.boundingBox!!)).map { (text, at) ->
                PosterReading(text, at, w.confidence.orSure())
            }
        }
        return rooms.ifEmpty { listOf(whole) }
    }

    /**
     * Room numbers inside one ML Kit word, each with its share of the word's box: thin walls
     * read as '|' ("3423|3421|3419") and a row of small labels can run together
     * ("361736193621362" → 3617, 3619, 3621). Stray marks next to a number are dropped.
     */
    internal fun roomTokens(text: String, box: Rect): List<Pair<String, Rect>> {
        if (text.isEmpty()) return emptyList()
        val perChar = box.width().toDouble() / text.length
        val out = mutableListOf<Pair<String, Rect>>()
        fun add(token: String, start: Int) {
            val room = normalizeRoom(token) ?: return
            val left = box.left + (start * perChar).toInt()
            val right = box.left + ((start + token.length) * perChar).toInt()
            out += room to Rect(left, box.top, maxOf(right, left + 1), box.bottom)
        }
        Regex("[^|!:;\\s]+").findAll(text).forEach { part ->
            val t = part.value
            if (normalizeRoom(t) != null) return@forEach add(t, part.range.first)
            val digits = Regex("[0-9]{4}[A-Z]?").findAll(t)
            // Only split a run that is all room-shaped chunks plus at most a short tail.
            val covered = digits.sumOf { it.value.length }
            if (covered >= 8 && t.length - covered <= 3) {
                digits.forEach { add(it.value, part.range.first + it.range.first) }
            }
        }
        return out
    }

    /**
     * Whether [label] continues the numbering around it: at least two of the nearest room
     * numbers within [NEIGHBOUR_PX] share its first three digits (3536 among 3533, 3535, 3537).
     */
    internal fun fitsNeighbours(
        label: String,
        read: PosterReading,
        rooms: List<PosterReading>,
    ): Boolean {
        val run = label.take(RUN_DIGITS)
        val near = rooms.asSequence()
            .filter { it !== read && normalizeRoom(it.text) != label }
            .map { it to it.center.distanceTo(read.center) }
            .filter { (_, d) -> d <= NEIGHBOUR_PX }
            .sortedBy { it.second }
            .take(NEIGHBOURS)
            .map { normalizeRoom(it.first.text).orEmpty() }
            .toList()
        return near.count { it.startsWith(run) } >= MIN_SAME_RUN
    }

    /** ML Kit reports 0 when it has no confidence for a model; treat that as a plain read. */
    private fun Float.orSure(): Float = if (this > 0f) this else DEFAULT_CONFIDENCE

    private const val DEFAULT_CONFIDENCE = 0.95f

    /** Neighbour check: digits shared, how near, how many looked at, how many must match. */
    private const val RUN_DIGITS = 3
    private const val NEIGHBOUR_PX = 320.0
    private const val NEIGHBOURS = 4
    private const val MIN_SAME_RUN = 2

    /** Mosaic layout: cells enlarged to about this height, this far apart, on this width. */
    private const val CELL_HEIGHT = 80
    private const val MAX_SCALE = 4.0
    private const val GAP = 48
    private const val SHEET_WIDTH = 2400

    /** A small-patch read below this confidence is offered as a possible room only. */
    private const val SMALL_SURE = 0.8f

    /** At most this many leftover word patches get the slower recognizer (~0.1 s each). */
    private const val MAX_RECHECK = 12

    /**
     * ML Kit room reads at or above this are taken as right: on three EH posters it never read
     * a room number the PP-OCR reader didn't also find.
     */
    private const val MLKIT_SURE = 0.9f

    /** A room read on the review's scale that needs no look (its threshold is 0.97). */
    private const val SURE = 0.99f

    /** Margin around a doubtful room's box for the second reader. */
    private const val DOUBT_PAD = 4

    /** The second, wider crop of an exit-sign patch. */
    private const val SIGN_PAD = 6

    private fun padded(r: Rect, pad: Int) = Rect(
        r.left - pad,
        r.top - pad,
        r.right + pad,
        r.bottom + pad
    )
}

/**
 * Which turn (a Core.ROTATE_* code) makes a poster's print level, or null if it already reads
 * best as it is. ML Kit reads the poster each way round (about half a second each); used only
 * when a parse found almost no rooms, e.g. a landscape plan hung in a portrait frame.
 */
internal fun uprightTurn(poster: Bitmap): Int? {
    val level = PosterMlKit.read(poster).count { normalizeRoom(it.text) != null }
    return listOf(
        Core.ROTATE_90_CLOCKWISE to 90,
        Core.ROTATE_180 to 180,
        Core.ROTATE_90_COUNTERCLOCKWISE to 270,
    ).map { (code, degrees) -> code to PosterMlKit.roomsTurned(poster, degrees) }
        .maxByOrNull { it.second }
        ?.takeIf { (_, rooms) -> rooms >= MIN_TURNED_ROOMS && rooms > 2 * level }
        ?.first
}

private const val MIN_TURNED_ROOMS = 3
