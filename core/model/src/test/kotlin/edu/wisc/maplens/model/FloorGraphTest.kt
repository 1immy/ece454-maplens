package edu.wisc.maplens.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloorGraphTest {
    @Test
    fun `valid graph has no problems`() {
        assertEquals(emptyList(), TestGraphs.tJunction().validate())
    }

    @Test
    fun `missing start node is reported`() {
        val graph = TestGraphs.tJunction().copy(startNodeId = "nope")
        assertTrue(graph.validate().any { "start node" in it })
    }

    @Test
    fun `disconnected node is reported`() {
        val base = TestGraphs.tJunction()
        val island = Node("X", PixelPoint(900.0, 900.0), NodeType.DEAD_END)
        val graph = base.copy(nodes = base.nodes + (island.id to island))
        assertTrue(graph.validate().any { "not connected" in it && "X" in it })
    }

    @Test
    fun `landmark lookup finds door`() {
        assertEquals("D3650", TestGraphs.tJunction().landmark("3650")?.nodeId)
    }
}
