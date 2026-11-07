package edu.wisc.maplens

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapStoreTest {
    private val root: File = Files.createTempDirectory("maps").toFile()
    private val store = MapStore(root)

    private val graph = run {
        val s = Node("S", PixelPoint(0.0, 0.0), NodeType.START)
        val d = Node("D", PixelPoint(100.0, 0.0), NodeType.DOOR)
        FloorGraph(
            buildingId = "engineering-hall",
            floor = "3",
            nodes = listOf(s, d).associateBy { it.id },
            edges = listOf(Edge("e1", "S", "D", listOf(s.position, d.position), 100.0)),
            landmarks = listOf(Landmark("3546", "D", 0.98f), Landmark("OFFMAP", "D", 1f)),
            startNodeId = "S",
            verified = true,
        )
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun image(file: File) = file.writeBytes(byteArrayOf(1, 2, 3))

    @Test
    fun `a saved map round-trips and lists with its metadata`() {
        val saved = store.save(graph, "EH3 elevators", nowMillis = 1_000L, writeImage = ::image)
        assertEquals("map-1000", saved.id)
        assertEquals("EH3 elevators", saved.name)
        assertEquals(1, saved.rooms)
        assertEquals(listOf(saved), store.list())
        assertEquals(graph, store.loadGraph(saved))
        assertEquals(3L, saved.imageFile.length())
        assertEquals(saved, store.get("map-1000"))
    }

    @Test
    fun `a new store on the same folder sees earlier maps, newest first`() {
        store.save(graph, "first", nowMillis = 1_000L, writeImage = ::image)
        store.save(graph, "second", nowMillis = 2_000L, writeImage = ::image)
        store.save(graph, "same time", nowMillis = 2_000L, writeImage = ::image)
        val reopened = MapStore(root).list()
        assertEquals(3, reopened.size)
        assertEquals("first", reopened.last().name)
        assertEquals(setOf("map-2000", "map-2000-1"), reopened.take(2).map { it.id }.toSet())
    }

    @Test
    fun `unverified drafts are refused`() {
        assertFailsWith<IllegalArgumentException> {
            store.save(graph.copy(verified = false), "draft", writeImage = ::image)
        }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `a failed image write leaves nothing behind`() {
        assertFailsWith<IllegalStateException> {
            store.save(graph, "broken", nowMillis = 5L) { error("disk full") }
        }
        assertFailsWith<IllegalStateException> {
            store.save(graph, "empty image", nowMillis = 6L) { it.writeBytes(ByteArray(0)) }
        }
        assertTrue(store.list().isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `incomplete folders are skipped and maps can be deleted`() {
        File(root, "map-9").mkdirs()
        val saved = store.save(graph, "kept", nowMillis = 10L, writeImage = ::image)
        assertEquals(listOf("map-10"), store.list().map { it.id })
        assertTrue(store.delete(saved.id))
        assertNull(store.get(saved.id))
    }
}
