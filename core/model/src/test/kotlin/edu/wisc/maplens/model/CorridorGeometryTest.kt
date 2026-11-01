package edu.wisc.maplens.model

import java.io.File
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorridorGeometryTest {
    private val graph = TestGraphs.tJunction()
    private val tol = 1e-9

    /** An L-shaped corridor: right 100 px, then down 100 px. */
    private val bent = FloorGraph(
        buildingId = "t",
        floor = "1",
        nodes = listOf(
            Node("A", PixelPoint(0.0, 0.0), NodeType.START),
            Node("B", PixelPoint(100.0, 100.0), NodeType.DEAD_END),
        ).associateBy { it.id },
        edges = listOf(
            Edge(
                "L",
                "A",
                "B",
                listOf(PixelPoint(0.0, 0.0), PixelPoint(100.0, 0.0), PixelPoint(100.0, 100.0)),
                200.0,
            ),
        ),
        landmarks = emptyList(),
        startNodeId = "A",
    )

    @Test
    fun `bearings follow the y-down image convention`() {
        assertEquals(0.0, bearing(PixelPoint(0.0, 0.0), PixelPoint(10.0, 0.0)), tol) // right
        assertEquals(PI / 2, bearing(PixelPoint(0.0, 0.0), PixelPoint(0.0, 10.0)), tol) // down
        assertEquals(-PI / 2, bearing(PixelPoint(0.0, 0.0), PixelPoint(0.0, -10.0)), tol) // up
        assertEquals(PI, bearing(PixelPoint(0.0, 0.0), PixelPoint(-10.0, 0.0)), tol) // left
    }

    @Test
    fun `wrap and difference stay in range`() {
        assertEquals(-PI / 2, wrapBearing(3 * PI / 2), tol)
        assertEquals(PI, wrapBearing(-PI), tol)
        assertEquals(0.2, bearingDifference(PI - 0.1, -PI + 0.1), 1e-9)
    }

    @Test
    fun `point and bearing along a straight edge in both directions`() {
        // e3 runs J (200, 0) -> E (400, 0)
        val p = graph.pointOnEdge("e3", 50.0)
        assertEquals(250.0, p.x, tol)
        assertEquals(0.0, p.y, tol)
        assertEquals(0.0, graph.bearingOnEdge("e3", 50.0, towardTo = true), tol)
        assertEquals(PI, graph.bearingOnEdge("e3", 50.0, towardTo = false), tol)
    }

    @Test
    fun `bearing follows the polyline around a bend`() {
        assertEquals(0.0, bent.bearingOnEdge("L", 50.0), tol)
        assertEquals(PI / 2, bent.bearingOnEdge("L", 150.0), tol)
        val p = bent.pointOnEdge("L", 150.0)
        assertEquals(100.0, p.x, tol)
        assertEquals(50.0, p.y, tol)
    }

    @Test
    fun `offsets past the ends are clamped`() {
        assertEquals(PixelPoint(200.0, 0.0), graph.pointOnEdge("e3", -10.0))
        assertEquals(PixelPoint(400.0, 0.0), graph.pointOnEdge("e3", 999.0))
    }

    @Test
    fun `corridors at a junction give every way out`() {
        val options = graph.corridorsAt("J").associate { it.towardNodeId to it.bearingRad }
        assertEquals(setOf("D3650", "E", "S"), options.keys)
        assertEquals(PI, options.getValue("D3650"), tol) // left
        assertEquals(0.0, options.getValue("E"), tol) // right
        assertEquals(PI / 2, options.getValue("S"), tol) // down toward START
    }

    @Test
    fun `corridors at START face up toward the junction`() {
        val options = graph.corridorsAt("S")
        assertEquals(1, options.size)
        assertEquals("J", options.single().towardNodeId)
        assertEquals(-PI / 2, options.single().bearingRad, tol)
    }

    @Test
    fun `nearest edge snaps a point onto the corridor`() {
        val hit = assertNotNull(graph.nearestEdge(PixelPoint(260.0, 12.0)))
        assertEquals("e3", hit.edgeId)
        assertEquals(60.0, hit.offsetPx, tol)
        assertEquals(12.0, hit.distancePx, tol)
        assertEquals(260.0, hit.point.x, tol)
        assertEquals(0.0, hit.point.y, tol)
    }

    @Test
    fun `nearest edge offset follows the polyline of a bent edge`() {
        val hit = assertNotNull(bent.nearestEdge(PixelPoint(110.0, 40.0)))
        assertEquals(140.0, hit.offsetPx, tol)
        assertEquals(10.0, hit.distancePx, tol)
    }

    /** Real EH3 graph: every node sits on the network and START has corridors to face. */
    @Test
    fun `traced EH3 graph works with the geometry helpers`() {
        val root = System.getProperty("maplens.fixtures") ?: return
        val file = File(root, "maps/eh-3-elevators-evac.graph.json")
        if (!file.exists()) return
        val eh3 = FloorGraphJson.decode(file.readText())
        assertTrue(eh3.corridorsAt(eh3.startNodeId).isNotEmpty())
        for (node in eh3.nodes.values) {
            val hit = assertNotNull(eh3.nearestEdge(node.position))
            assertTrue(hit.distancePx < 1e-6, "node ${node.id} is ${hit.distancePx} px off")
        }
        for (edge in eh3.edges) {
            val mid = eh3.pointOnEdge(edge.id, edge.lengthPx / 2)
            val back = assertNotNull(eh3.nearestEdge(mid))
            assertTrue(back.distancePx < 1e-6)
        }
    }
}
