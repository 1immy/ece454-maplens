package edu.wisc.maplens.nav

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
     */
    fun labels(image: Bitmap, known: Set<String>): List<LabelBox> {
        val floor = known.groupingBy { it.first() }.eachCount().maxByOrNull { it.value }?.key
        val accept = { label: String, alone: Boolean ->
            label in known ||
                (alone && label.first() == floor && ROOM.matches(label))
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val found = LinkedHashMap<String, LabelBox>()
        try {
            read(recognizer, image, 0, 0, 1f, accept, found)
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
                    read(recognizer, big, left, top, TILE_ZOOM, accept, found)
                    big.recycle()
                    piece.recycle()
                    left += tile - TILE_OVERLAP_PX
                }
                top += tile - TILE_OVERLAP_PX
            }
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

    private fun read(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: Bitmap,
        left: Int,
        top: Int,
        zoom: Float,
        accept: (String, Boolean) -> Boolean,
        found: MutableMap<String, LabelBox>,
    ) {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)))
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val label = normalizeLabel(element.text)
                    if (label in found || !accept(label, line.elements.size == 1)) continue
                    val box = element.boundingBox ?: continue
                    found[label] = LabelBox(
                        label,
                        left + box.left / zoom.toDouble(),
                        top + box.top / zoom.toDouble(),
                        left + box.right / zoom.toDouble(),
                        top + box.bottom / zoom.toDouble(),
                    )
                }
            }
        }
    }

    private val ROOM = Regex("""\d{3,4}[A-Z]?""")
    private const val TILE_PX = 700
    private const val TILE_OVERLAP_PX = 120
    private const val TILE_ZOOM = 1.8f
}
