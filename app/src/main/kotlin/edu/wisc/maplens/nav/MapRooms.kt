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
     * numbers in narrow rooms are read too; the first box per label is kept.
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
        val floor = known.groupingBy { it.first() }.eachCount().maxByOrNull { it.value }?.key
        val accept = { label: String, alone: Boolean ->
            label in known ||
                (alone && label.first() == floor && ROOM.matches(label))
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val found = LinkedHashMap<String, LabelBox>()
        try {
            read(recognizer, image, 0, 0, 1f, accept, found, known)
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
                    read(recognizer, big, left, top, TILE_ZOOM, accept, found, known)
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
                read(recognizer, big, left, top, CLOSE_ZOOM, missing, found, known)
                big.recycle()
                piece.recycle()
            }
        }
    }

    private fun read(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: Bitmap,
        left: Int,
        top: Int,
        zoom: Float,
        accept: (String, Boolean) -> Boolean,
        found: MutableMap<String, LabelBox>,
        candidates: Collection<String>,
    ) {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)))
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val label = normalizeLabel(element.text)
                    val box = element.boundingBox ?: continue
                    fun keep(part: String, from: Int, to: Int, length: Int) {
                        // Digits are near enough equal width to place a part by its characters.
                        // A part cut from a run keeps only its middle, so the box a room grows
                        // from starts inside that room, not on the thin wall beside it.
                        val trim = if (to - from < length) PART_TRIM * (to - from) else 0.0
                        val x0 = box.left + box.width() * (from + trim) / length
                        val x1 = box.left + box.width() * (to - trim) / length
                        found[part] = LabelBox(
                            part,
                            left + x0 / zoom,
                            top + box.top / zoom.toDouble(),
                            left + x1 / zoom,
                            top + box.bottom / zoom.toDouble(),
                        )
                    }
                    if (label !in found && accept(label, line.elements.size == 1)) {
                        keep(label, 0, label.length, label.length)
                        continue
                    }
                    // Numbers in narrow neighbouring rooms, split only by a thin wall, come back
                    // run together ("343834363434"): cut the run back into room numbers.
                    for ((part, from, to) in splitRun(label, candidates)) {
                        if (part !in found && accept(part, true)) keep(part, from, to, label.length)
                    }
                }
            }
        }
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
    private const val TILE_PX = 700
    private const val TILE_OVERLAP_PX = 120
    private const val TILE_ZOOM = 1.8f
    private const val CLOSE_PX = 260
    private const val PART_TRIM = 0.2
    private const val CLOSE_ZOOM = 3.2f
}
