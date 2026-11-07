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

class DraftStoreTest {
    private val root = Files.createTempDirectory("maps").toFile()
    private val draft = DraftStore(File(root, "draft"))
    private val maps = MapStore(File(root, "maps"))
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
        root.deleteRecursively()
    }

    @Test
    fun `a draft survives being read back with its rooms`() {
        draft.start(MapDraft(graph, rooms), image("photo"))
        assertTrue(draft.exists)
        assertEquals(MapDraft(graph, rooms), DraftStore(File(root, "draft")).load())
        assertEquals("photo", draft.image.readText())
    }

    @Test
    fun `edits to a draft replace the earlier ones`() {
        draft.start(MapDraft(graph, rooms), image("photo"))
        draft.update(MapDraft(graph.deleteNode("D"), emptyList()))
        val loaded = draft.load()
        assertFalse("D" in loaded.graph.nodes)
        assertTrue(loaded.rooms.isEmpty())
    }

    @Test
    fun `a new draft replaces an unfinished one`() {
        draft.start(MapDraft(graph, rooms), image("old"))
        draft.start(MapDraft(graph, emptyList()), image("new"))
        assertEquals("new", draft.image.readText())
        assertTrue(draft.load().rooms.isEmpty())
    }

    @Test
    fun `saving moves the map and its image to the saved maps`() {
        draft.start(MapDraft(graph, rooms), image("photo"))
        val saved = draft.saveTo(maps, graph.copy(verified = true), "Scan")
        assertFalse(draft.exists)
        assertEquals("photo", saved.imageFile.readText())
        assertEquals(listOf(saved.id), maps.list().map { it.id })
        assertTrue(maps.loadGraph(saved).verified)
    }

    @Test
    fun `a refused save keeps the draft and lists nothing`() {
        draft.start(MapDraft(graph, rooms), image("photo"))
        assertFailsWith<IllegalArgumentException> { draft.saveTo(maps, graph, "Scan") }
        assertTrue(draft.exists)
        assertEquals("photo", draft.image.readText())
        assertTrue(maps.list().isEmpty())
    }

    @Test
    fun `a failed image copy keeps the draft and lists nothing`() {
        draft.start(MapDraft(graph, rooms), image("photo"))
        draft.image.delete()
        assertFailsWith<Exception> { draft.saveTo(maps, graph.copy(verified = true), "Scan") }
        assertTrue(draft.exists)
        assertTrue(maps.list().isEmpty())
    }
}
