package edu.wisc.maplens

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.routing.roomDestinations
import java.io.File
import java.util.Properties

/** One map saved on the phone: a verified graph and the straightened image it refers to. */
data class SavedMap(
    val id: String,
    val name: String,
    val savedAtMillis: Long,
    val rooms: Int,
    val graphFile: File,
    val imageFile: File,
)

/**
 * Verified maps kept in app storage, so they work offline and survive a restart (#65, #20).
 *
 * One folder per map under [root]: `graph.json` (FloorGraphJson), `image.jpg` (the straightened
 * poster the graph's pixels refer to) and `map.properties` (name, save time, room count). A map
 * is written to a temporary folder and renamed into place, so a half-written map never lists.
 *
 * Plain java.io, no Android, so it unit-tests on a laptop. Call it off the UI thread.
 */
class MapStore(private val root: File) {
    /**
     * Saves [graph], which must be verified (`NavigationSession.onGraphVerified`). [writeImage]
     * writes the straightened image to the file it is given.
     */
    fun save(
        graph: FloorGraph,
        name: String,
        nowMillis: Long = System.currentTimeMillis(),
        writeImage: (File) -> Unit,
    ): SavedMap {
        require(graph.verified) { "only verified graphs can be saved" }
        root.mkdirs()
        var id = "map-$nowMillis"
        var suffix = 1
        while (File(root, id).exists()) id = "map-$nowMillis-${suffix++}"
        val staging = File(root, ".$id.tmp")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "cannot create $staging" }
        try {
            File(staging, GRAPH).writeText(FloorGraphJson.encode(graph))
            writeImage(File(staging, IMAGE))
            check(File(staging, IMAGE).length() > 0) { "image was not written" }
            val meta = Properties().apply {
                setProperty("name", name)
                setProperty("savedAtMillis", nowMillis.toString())
                setProperty("rooms", graph.roomDestinations().size.toString())
            }
            File(staging, META).outputStream().use { meta.store(it, "MapLens saved map") }
            check(staging.renameTo(File(root, id))) { "cannot move $staging into place" }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
        return checkNotNull(read(File(root, id)))
    }

    /** Every complete saved map, newest first. */
    fun list(): List<SavedMap> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .mapNotNull(::read)
            .sortedByDescending { it.savedAtMillis }

    fun get(id: String): SavedMap? = File(root, id).takeIf { it.isDirectory }?.let(::read)

    /** The saved graph, still verified. */
    fun loadGraph(map: SavedMap): FloorGraph = FloorGraphJson.decode(map.graphFile.readText())

    fun delete(id: String): Boolean = File(root, id).deleteRecursively()

    private fun read(dir: File): SavedMap? {
        val graph = File(dir, GRAPH)
        val image = File(dir, IMAGE)
        val metaFile = File(dir, META)
        if (!graph.isFile || !image.isFile || !metaFile.isFile) return null
        val meta = Properties().apply { metaFile.inputStream().use { load(it) } }
        return SavedMap(
            id = dir.name,
            name = meta.getProperty("name") ?: dir.name,
            savedAtMillis = meta.getProperty("savedAtMillis")?.toLongOrNull() ?: 0L,
            rooms = meta.getProperty("rooms")?.toIntOrNull() ?: 0,
            graphFile = graph,
            imageFile = image,
        )
    }

    private companion object {
        const val GRAPH = "graph.json"
        const val IMAGE = "image.jpg"
        const val META = "map.properties"
    }
}
