package edu.wisc.maplens

import android.content.Context
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import java.io.File

/**
 * The map on this phone: one draft being checked and one verified map, each a graph JSON beside
 * its straightened image. Call off the UI thread. See ADR 0008.
 *
 * shortcut: one verified map, replaced on each save. Upgrade to one folder per map with a picker
 * when more than one floor is in scope.
 */
internal class MapStore(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "maps"))

    private val draft = File(root, "draft")
    private val saved = File(root, "saved")
    private val incoming = File(root, "saved.tmp")
    private val outgoing = File(root, "saved.old")

    val draftImage get() = File(draft, IMAGE)

    /** Saved images survive a crash between the two renames in [saveVerified]. */
    val savedImage get() = File(if (saved.exists()) saved else outgoing, IMAGE)

    val hasDraft get() = File(draft, GRAPH).exists()
    val hasSaved get() = File(saved, GRAPH).exists() || File(outgoing, GRAPH).exists()

    /** Starts a new draft, replacing any unfinished one. */
    fun startDraft(map: MapDraft, writeImage: (File) -> Unit) {
        draft.deleteRecursively()
        check(draft.mkdirs()) { "could not create $draft" }
        writeImage(draftImage)
        updateDraft(map)
    }

    fun updateDraft(map: MapDraft) {
        File(draft, GRAPH).writeAtomically(FloorGraphJson.encode(map.graph))
        File(draft, ROOMS).writeAtomically(encodeRooms(map.rooms))
    }

    fun loadDraft(): MapDraft {
        val graph = FloorGraphJson.decode(File(draft, GRAPH).readText())
        val rooms = File(draft, ROOMS).takeIf { it.exists() }?.readText()?.let(::decodeRooms)
        return MapDraft(graph, rooms.orEmpty()).withGraph(graph)
    }

    /** Replaces the verified map as one step, then drops the draft it came from. */
    fun saveVerified(graph: FloorGraph) {
        require(graph.verified) { "only a verified map can be saved" }
        incoming.deleteRecursively()
        check(incoming.mkdirs()) { "could not create $incoming" }
        draftImage.copyTo(File(incoming, IMAGE))
        File(incoming, GRAPH).writeText(FloorGraphJson.encode(graph))
        outgoing.deleteRecursively()
        if (saved.exists()) check(saved.renameTo(outgoing)) { "could not replace $saved" }
        check(incoming.renameTo(saved)) { "could not write $saved" }
        outgoing.deleteRecursively()
        draft.deleteRecursively()
    }

    /** The verified map. A file that isn't marked verified never reaches navigation. */
    fun loadSaved(): FloorGraph {
        val file = File(if (saved.exists()) saved else outgoing, GRAPH)
        val graph = FloorGraphJson.decode(file.readText())
        check(graph.verified) { "saved map is not verified" }
        return graph
    }

    private fun File.writeAtomically(text: String) {
        val temporary = File(parentFile, "$name.tmp")
        temporary.writeText(text)
        check(temporary.renameTo(this)) { "could not write $this" }
    }

    private companion object {
        const val GRAPH = "graph.json"
        const val IMAGE = "map.jpg"
        const val ROOMS = "rooms.tsv"
    }
}

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
