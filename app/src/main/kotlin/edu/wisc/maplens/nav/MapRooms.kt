package edu.wisc.maplens.nav

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.routing.normalizeLabel

/** Android glue for [RoomFinder]: walls from the map image, room numbers from ML Kit. */
internal object MapRooms {
    private const val TAG = "MapRooms"

    /** Wall grid at [scale] map px per cell, from the image's luminance. Off the UI thread. */
    fun walls(image: Bitmap, scale: Int = 2): WallGrid {
        val w = image.width / scale
        val h = image.height / scale
        val small = Bitmap.createScaledBitmap(image, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== image) small.recycle()
        val luma = IntArray(w * h) {
            val c = pixels[it]
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return WallGrid.fromLuminance(luma, w, h, scale)
    }

    /**
     * Where room numbers are printed on the map, read on-device with ML Kit. Blocks, so call
     * it off the UI thread. The whole map is read once, then in enlarged tiles so the small
     * numbers in narrow rooms are read too; per label, the box nearest its door is kept.
     *
     * Besides the [known] rooms of the graph it keeps any other number of the same floor
     * standing alone on its line, so a room the traced graph lacks can still be named when
     * tapped. Numbers inside running text (the AED legend's list) are skipped.
     *
     * Rooms the graph knows but no pass could read (small print in narrow rooms) get a last,
     * close-up look: a small crop around each of their [doors], enlarged further, where only
     * the still-missing numbers are accepted. Only those rooms are re-read, so it stays quick.
     */
    fun labels(
        image: Bitmap,
        known: Set<String>,
        doors: Map<String, List<PixelPoint>> = emptyMap(),
    ): List<LabelBox> {
        val accept = extraRoomRule(known)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val found = LinkedHashMap<String, LabelBox>()
        try {
            read(recognizer, image, 0, 0, 1f, accept, found, known, doors)
            val tile = TILE_PX
            var top = 0
            while (top < image.height) {
                var left = 0
                while (left < image.width) {
                    val w = minOf(tile, image.width - left)
                    val h = minOf(tile, image.height - top)
                    val piece = Bitmap.createBitmap(image, left, top, w, h)
                    val big = Bitmap.createScaledBitmap(
                        piece,
                        (w * TILE_ZOOM).toInt(),
                        (h * TILE_ZOOM).toInt(),
                        true,
                    )
                    read(recognizer, big, left, top, TILE_ZOOM, accept, found, known, doors)
                    big.recycle()
                    piece.recycle()
                    left += tile - TILE_OVERLAP_PX
                }
                top += tile - TILE_OVERLAP_PX
            }
            closeUp(recognizer, image, known, doors, found)
        } catch (failure: Exception) {
            // Tapping still works from doors alone; the read numbers only make it sharper.
            Log.w(TAG, "Room numbers could not be read", failure)
        } finally {
            recognizer.close()
        }
        val extra = found.keys - known
        Log.i(TAG, "Read ${found.size - extra.size} of ${known.size} graph rooms, plus $extra")
        return found.values.toList()
    }

    private fun closeUp(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: Bitmap,
        known: Set<String>,
        doors: Map<String, List<PixelPoint>>,
        found: MutableMap<String, LabelBox>,
    ) {
        val missing = { label: String, _: Boolean -> label in known && label !in found }
        for ((label, points) in doors) {
            if (label in found) continue
            for (door in points) {
                if (label in found) break
                val half = CLOSE_PX / 2
                val left = (door.x.toInt() - half).coerceIn(0, maxOf(0, image.width - CLOSE_PX))
                val top = (door.y.toInt() - half).coerceIn(0, maxOf(0, image.height - CLOSE_PX))
                val w = minOf(CLOSE_PX, image.width - left)
                val h = minOf(CLOSE_PX, image.height - top)
                val piece = Bitmap.createBitmap(image, left, top, w, h)
                val big = Bitmap.createScaledBitmap(
                    piece,
                    (w * CLOSE_ZOOM).toInt(),
                    (h * CLOSE_ZOOM).toInt(),
                    true,
                )
                read(recognizer, big, left, top, CLOSE_ZOOM, missing, found, known, doors)
                big.recycle()
                piece.recycle()
            }
        }
    }

    /**
     * Reads one image (a tile [zoom]ed from [left], [top] of the map) into [found].
     *
     * A suffix letter read as its own element ("3540" "B") joins its number, so "3540B" is
     * never taken for room 3540. When a label is read twice, the box nearer one of the room's
     * [doors] wins over the first one read, so a pin lands on the room's own number.
     */
    private fun read(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: Bitmap,
        left: Int,
        top: Int,
        zoom: Float,
        accept: (String, Boolean) -> Boolean,
        found: MutableMap<String, LabelBox>,
        candidates: Collection<String>,
        doors: Map<String, List<PixelPoint>>,
    ) {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)))
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (word in joinSuffixes(line.elements.mapNotNull(::wordOf))) {
                    val label = word.text
                    fun keep(part: String, from: Int, to: Int, length: Int) {
                        // Digits are near enough equal width to place a part by its characters.
                        // A part cut from a run keeps only its middle, so the box a room grows
                        // from starts inside that room, not on the thin wall beside it.
                        val trim = if (to - from < length) PART_TRIM * (to - from) else 0.0
                        val width = word.right - word.left
                        val x0 = word.left + width * (from + trim) / length
                        val x1 = word.left + width * (to - trim) / length
                        val candidate = LabelBox(
                            part,
                            left + x0 / zoom,
                            top + word.top / zoom.toDouble(),
                            left + x1 / zoom,
                            top + word.bottom / zoom.toDouble(),
                        )
                        val old = found[part]
                        if (old == null || closer(candidate, old, doors[part].orEmpty())) {
                            found[part] = candidate
                        }
                    }
                    if (accept(label, line.elements.size == 1 || word.joined)) {
                        keep(label, 0, label.length, label.length)
                        continue
                    }
                    // Numbers in narrow neighbouring rooms, split only by a thin wall, come back
                    // run together ("343834363434"): cut the run back into room numbers.
                    for ((part, from, to) in splitRun(label, candidates)) {
                        if (accept(part, true)) keep(part, from, to, label.length)
                    }
                }
            }
        }
    }

    /**
     * Which reads count as rooms: the [known] rooms, plus a number standing alone that looks
     * like this map's own (same floor digit, same digit count). A tile edge cutting through
     * "3418" leaves "341", which is not a room, so a read a digit short is never kept.
     */
    fun extraRoomRule(known: Set<String>): (String, Boolean) -> Boolean {
        val floor = known.groupingBy { it.first() }.eachCount().maxByOrNull { it.value }?.key
        val digits = known.groupingBy { label -> label.count(Char::isDigit) }.eachCount()
            .maxByOrNull { it.value }?.key
        return { label, alone ->
            label in known ||
                (
                    alone && label.first() == floor && ROOM.matches(label) &&
                        label.count(Char::isDigit) == digits
                    )
        }
    }

    /** One recognised word: normalised text and its box in the read image. */
    data class Word(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val joined: Boolean = false,
    )

    private fun wordOf(element: com.google.mlkit.vision.text.Text.Element): Word? =
        element.boundingBox?.let {
            Word(normalizeLabel(element.text), it.left, it.top, it.right, it.bottom)
        }

    /**
     * Joins a lone letter to the room number just left of it on the same line ("3540" "B" →
     * "3540B"), when the gap between them is under one character height.
     */
    fun joinSuffixes(words: List<Word>): List<Word> {
        val out = mutableListOf<Word>()
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val next = words.getOrNull(i + 1)
            val gap = next?.let { it.left - word.right }
            if (next != null && gap != null && next.text.length == 1 && next.text[0].isLetter() &&
                gap <= word.bottom - word.top && ROOM.matches(word.text + next.text)
            ) {
                out += Word(
                    word.text + next.text,
                    minOf(word.left, next.left),
                    minOf(word.top, next.top),
                    maxOf(word.right, next.right),
                    maxOf(word.bottom, next.bottom),
                    joined = true,
                )
                i += 2
            } else {
                out += word
                i++
            }
        }
        return out
    }

    /** True when [candidate] sits nearer the room's nearest door than [old] does. */
    fun closer(candidate: LabelBox, old: LabelBox, doors: List<PixelPoint>): Boolean {
        if (doors.isEmpty()) return false
        fun reach(box: LabelBox) = doors.minOf { it.distanceTo(box.center) }
        return reach(candidate) + SAME_PLACE_PX < reach(old)
    }

    /**
     * Room numbers found inside a run of characters, left to right, longest match first,
     * skipping characters that start none (a misread digit), with their character ranges.
     * Only runs at least twice a room number long are cut, so a single number is never split.
     */
    fun splitRun(run: String, candidates: Collection<String>): List<Triple<String, Int, Int>> {
        val shortest = candidates.minOfOrNull { it.length } ?: return emptyList()
        if (run.length < 2 * shortest) return emptyList()
        val byLength = candidates.sortedByDescending { it.length }
        val parts = mutableListOf<Triple<String, Int, Int>>()
        var i = 0
        while (i < run.length) {
            val match = byLength.firstOrNull { run.startsWith(it, i) }
            if (match == null) {
                i++
            } else {
                parts += Triple(match, i, i + match.length)
                i += match.length
            }
        }
        return parts
    }

    private val ROOM = Regex("""\d{3,4}[A-Z]?""")

    /** Two reads of a label this close are the same print; the first one stays. */
    private const val SAME_PLACE_PX = 20.0
    private const val TILE_PX = 700
    private const val TILE_OVERLAP_PX = 120
    private const val TILE_ZOOM = 1.8f
    private const val CLOSE_PX = 260
    private const val PART_TRIM = 0.2
    private const val CLOSE_ZOOM = 3.2f
}
