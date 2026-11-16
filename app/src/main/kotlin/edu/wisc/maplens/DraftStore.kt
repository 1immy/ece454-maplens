package edu.wisc.maplens

import android.content.Context
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import java.io.File

/**
 * The one map being checked: its graph, its readings and its straightened image. Edits land here
 * as they happen, so they outlast the screen and the app. A finished map moves to [MapStore].
 * Plain java.io apart from [of], so it tests on a laptop. Call it off the UI thread.
 */
internal class DraftStore(private val folder: File) {
    val image get() = File(folder, IMAGE)
    val exists get() = File(folder, GRAPH).exists()

    /**
     * Starts a new draft, replacing any unfinished one. [editing] is the saved map it was opened
     * from, which the finished draft then replaces; null makes a new map.
     */
    fun start(map: MapDraft, editing: String? = null, writeImage: (File) -> Unit) {
        folder.deleteRecursively()
        check(folder.mkdirs()) { "could not create $folder" }
        writeImage(image)
        editing?.let { File(folder, SOURCE).writeText(it) }
        update(map)
    }

    fun update(map: MapDraft) {
        File(folder, GRAPH).writeAtomically(FloorGraphJson.encode(map.graph))
        File(folder, ROOMS).writeAtomically(encodeRooms(map.rooms))
    }

    fun load(): MapDraft {
        val graph = FloorGraphJson.decode(File(folder, GRAPH).readText())
        val rooms = File(folder, ROOMS).takeIf { it.exists() }?.readText()?.let(::decodeRooms)
        return MapDraft(graph, rooms.orEmpty()).withGraph(graph)
    }

    /** Saves the verified map with this draft's image. The draft goes only once that worked. */
    fun saveTo(store: MapStore, verified: FloorGraph, name: String): SavedMap {
        val rooms = File(folder, ROOMS).takeIf { it.exists() }?.readText()?.let(::decodeRooms)
        val labels = rooms.orEmpty().map {
            SavedLabel(
                it.label,
                it.left,
                it.top,
                it.right,
                it.bottom
            )
        }
        val editing = File(folder, SOURCE).takeIf { it.exists() }?.readText()
        val saved = if (editing != null && store.get(editing) != null) {
            store.replace(editing, verified, labels)
        } else {
            store.save(verified, name, labels = labels) { image.copyTo(it) }
        }
        return saved.also { folder.deleteRecursively() }
    }

    private fun File.writeAtomically(text: String) {
        val temporary = File(parentFile, "$name.tmp")
        temporary.writeText(text)
        check(temporary.renameTo(this)) { "could not write $this" }
    }

    companion object {
        private const val GRAPH = "graph.json"
        private const val IMAGE = "map.jpg"
        private const val ROOMS = "rooms.tsv"
        private const val SOURCE = "editing.txt"

        /** Beside the saved maps, not among them, so it never lists as one. */
        fun of(context: Context) = DraftStore(File(context.filesDir, "map-draft"))
    }
}

/**
 * Saved map [graph] back as a draft to edit: each printed number in [labels] with the door its
 * label names, already accepted, since the map was verified when it was saved.
 */
internal fun draftOf(graph: FloorGraph, labels: List<SavedLabel>): MapDraft = MapDraft(
    graph.copy(verified = false),
    labels.map { printed ->
        val door = graph.landmarks.firstOrNull { it.label == printed.label }
        RoomDraft(
            printed.label,
            printed.left,
            printed.top,
            printed.right,
            printed.bottom,
            door?.nodeId,
            door?.ocrConfidence ?: 1f,
            checked = door != null,
        )
    },
)

/** One room per line, tab separated. OCR labels and node ids hold no tabs or line breaks. */
internal fun encodeRooms(rooms: List<RoomDraft>): String = rooms.joinToString("\n") { room ->
    listOf(
        room.label,
        room.left,
        room.top,
        room.right,
        room.bottom,
        room.entranceNodeId.orEmpty(),
        room.confidence,
        room.possible,
        room.checked,
    ).joinToString("\t")
}

internal fun decodeRooms(text: String): List<RoomDraft> =
    text.lineSequence().filter { it.isNotEmpty() }.map { line ->
        val f = line.split('\t')
        RoomDraft(
            f[0],
            f[1].toInt(),
            f[2].toInt(),
            f[3].toInt(),
            f[4].toInt(),
            f[5].ifEmpty { null },
            f[6].toFloat(),
            f[7].toBoolean(),
            f[8].toBoolean(),
        )
    }.toList()
