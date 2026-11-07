package edu.wisc.maplens

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapStoreTest {
    private val folder = Files.createTempDirectory("maps").toFile()
    private val store = MapStore(folder)
    private val graph = FloorGraph(
        "b",
        "3",
        listOf(
            Node("S", PixelPoint(0.0, 0.0), NodeType.START),
            Node("D", PixelPoint(100.0, 0.0), NodeType.DOOR),
        ).associateBy { it.id },
        listOf(
            Edge("e", "S", "D", listOf(PixelPoint(0.0, 0.0), PixelPoint(100.0, 0.0)), 100.0),
        ),
        emptyList(),
        "S",
    )
    private val rooms = listOf(
        RoomDraft("3546", 1, 2, 3, 4, "D", 0.9f),
        RoomDraft("3602A", 5, 6, 7, 8, null, 0.7f, possible = true),
        RoomDraft("3417", 9, 10, 11, 12, "D", 0.99f, checked = true),
    )

    private fun image(text: String): (File) -> Unit = { it.writeText(text) }

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun `a draft survives being read back with its rooms`() {
        store.startDraft(MapDraft(graph, rooms), image("photo"))
        assertTrue(store.hasDraft)
        assertEquals(MapDraft(graph, rooms), MapStore(folder).loadDraft())
        assertEquals("photo", store.draftImage.readText())
    }

    @Test
    fun `edits to a draft replace the earlier ones`() {
        store.startDraft(MapDraft(graph, rooms), image("photo"))
        store.updateDraft(MapDraft(graph.deleteNode("D"), emptyList()))
        val loaded = store.loadDraft()
        assertFalse("D" in loaded.graph.nodes)
        assertTrue(loaded.rooms.isEmpty())
    }

    @Test
    fun `a new draft replaces an unfinished one`() {
        store.startDraft(MapDraft(graph, rooms), image("old"))
        store.startDraft(MapDraft(graph, emptyList()), image("new"))
        assertEquals("new", store.draftImage.readText())
        assertTrue(store.loadDraft().rooms.isEmpty())
    }

    @Test
    fun `saving keeps the map with its image and clears the draft`() {
        store.startDraft(MapDraft(graph, rooms), image("photo"))
        store.saveVerified(graph.copy(verified = true))
        val after = MapStore(folder)
        assertTrue(after.hasSaved)
        assertFalse(after.hasDraft)
        assertTrue(after.loadSaved().verified)
        assertEquals("photo", after.savedImage.readText())
    }

    @Test
    fun `a second save replaces the first`() {
        store.startDraft(MapDraft(graph, rooms), image("first"))
        store.saveVerified(graph.copy(verified = true))
        store.startDraft(MapDraft(graph, rooms), image("second"))
        store.saveVerified(graph.copy(floor = "4", verified = true))
        assertEquals("4", store.loadSaved().floor)
        assertEquals("second", store.savedImage.readText())
    }

    @Test
    fun `an unverified map cannot be saved or loaded`() {
        store.startDraft(MapDraft(graph, rooms), image("photo"))
        assertFailsWith<IllegalArgumentException> { store.saveVerified(graph) }
        assertFalse(store.hasSaved)
        assertTrue(store.hasDraft)

        store.saveVerified(graph.copy(verified = true))
        val file = File(File(folder, "saved"), "graph.json")
        file.writeText(file.readText().replace("\"verified\": true", "\"verified\": false"))
        assertFailsWith<IllegalStateException> { store.loadSaved() }
    }

    @Test
    fun `a crash between the two renames still finds the old map`() {
        store.startDraft(MapDraft(graph, rooms), image("photo"))
        store.saveVerified(graph.copy(verified = true))
        assertTrue(File(folder, "saved").renameTo(File(folder, "saved.old")))
        assertTrue(store.hasSaved)
        assertTrue(store.loadSaved().verified)
        assertEquals("photo", store.savedImage.readText())
    }
}
