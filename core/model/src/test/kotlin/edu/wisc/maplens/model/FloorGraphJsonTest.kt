package edu.wisc.maplens.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FloorGraphJsonTest {
    @Test
    fun `encode then decode gives the same graph`() {
        val original = TestGraphs.tJunction().copy(verified = true)
        assertEquals(original, FloorGraphJson.decode(FloorGraphJson.encode(original)))
    }

    @Test
    fun `hand-traced minimal file fills in ids, polylines and lengths`() {
        val text = """
            {
              "schemaVersion": 1,
              "buildingId": "engineering-hall",
              "floor": "3",
              "startNodeId": "S",
              "nodes": [
                { "id": "S", "type": "START", "x": 200, "y": 150 },
                { "id": "J", "type": "JUNCTION", "x": 200, "y": 0 }
              ],
              "edges": [ { "from": "S", "to": "J" } ]
            }
        """.trimIndent()
        val graph = FloorGraphJson.decode(text)
        val edge = graph.edges.single()
        assertEquals("e1", edge.id)
        assertEquals(150.0, edge.lengthPx, 1e-9)
        assertEquals(2, edge.polyline.size)
        assertEquals(false, graph.verified)
        assertEquals(emptyList(), graph.validate())
    }

    @Test
    fun `wrong schema version is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            FloorGraphJson.decode("""{ "schemaVersion": 99, "nodes": [], "edges": [] }""")
        }
        assertTrue("schemaVersion" in error.message.orEmpty())
    }

    @Test
    fun `duplicate node ids are rejected`() {
        val text = """
            { "schemaVersion": 1, "buildingId": "b", "floor": "1", "startNodeId": "A",
              "nodes": [ { "id": "A", "type": "START", "x": 0, "y": 0 },
                         { "id": "A", "type": "DOOR", "x": 1, "y": 1 } ],
              "edges": [] }
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> { FloorGraphJson.decode(text) }
    }

    /** Guards hand-traced fixtures: every *.graph.json must load and pass validate(). */
    @Test
    fun `every fixture graph decodes and validates`() {
        val root = System.getProperty("maplens.fixtures") ?: return
        val files = File(root, "maps").listFiles { f -> f.name.endsWith(".graph.json") }.orEmpty()
        assertTrue(files.isNotEmpty(), "no *.graph.json fixtures found in $root/maps")
        for (file in files) {
            val problems = FloorGraphJson.decode(file.readText()).validate()
            assertEquals(emptyList(), problems, "fixture ${file.name}")
        }
    }
}
