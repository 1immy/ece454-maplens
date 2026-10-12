package edu.wisc.maplens.routing

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AStarRouterTest {
    private val router = AStarRouter()

    // Same T-junction as core/model's TestGraphs (test sources are not shared across modules).
    private val graph = run {
        fun n(id: String, x: Double, y: Double, t: NodeType) = Node(id, PixelPoint(x, y), t)
        val nodes = listOf(
            n("W", 0.0, 0.0, NodeType.DEAD_END),
            n("D3650", 100.0, 0.0, NodeType.DOOR),
            n("J", 200.0, 0.0, NodeType.JUNCTION),
            n("E", 400.0, 0.0, NodeType.DEAD_END),
            n("S", 200.0, 150.0, NodeType.START),
        ).associateBy { it.id }
        fun e(id: String, a: String, b: String): Edge {
            val pa = nodes.getValue(a).position
            val pb = nodes.getValue(b).position
            return Edge(id, a, b, listOf(pa, pb), pa.distanceTo(pb))
        }
        FloorGraph(
            buildingId = "engineering-hall",
            floor = "3",
            nodes = nodes,
            edges = listOf(
                e("e1", "W", "D3650"),
                e("e2", "D3650", "J"),
                e("e3", "J", "E"),
                e("e4", "S", "J"),
            ),
            landmarks = emptyList(),
            startNodeId = "S",
        )
    }

    @Test
    fun `routes from start to door through junction`() {
        val route = router.route(graph, "S", "D3650")!!
        assertEquals(listOf("S", "J", "D3650"), route.nodeIds)
        assertEquals(listOf("e4", "e2"), route.edgeIds)
        assertEquals(250.0, route.lengthPx, 1e-9)
    }

    @Test
    fun `same node gives empty zero-length route`() {
        val route = router.route(graph, "J", "J")!!
        assertEquals(listOf("J"), route.nodeIds)
        assertEquals(0.0, route.lengthPx, 1e-9)
    }

    @Test
    fun `unknown node returns null`() {
        assertNull(router.route(graph, "S", "missing"))
    }

    @Test
    fun `step count uses calibrated scale`() {
        val route = router.route(graph, "S", "D3650")!!
        assertEquals(10.0, route.lengthSteps(stepLengthPx = 25.0), 1e-9)
    }
}
