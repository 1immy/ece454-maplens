package edu.wisc.maplens.routing

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RerouteTest {
    private val router = AStarRouter()
    private val right = 0.0
    private val left = PI

    /**
     * A loop: L (0,0) - R (300,0) along the top, and L - BL (0,100) - BR (300,100) - R round
     * the bottom (500 px). Room "3418" has its door D on the bottom at (150,100). A dead-end
     * spur R - X (400,0) sticks out to the right.
     */
    private val graph = run {
        val points = mapOf(
            "L" to PixelPoint(0.0, 0.0),
            "R" to PixelPoint(300.0, 0.0),
            "BL" to PixelPoint(0.0, 100.0),
            "D" to PixelPoint(150.0, 100.0),
            "BR" to PixelPoint(300.0, 100.0),
            "X" to PixelPoint(400.0, 0.0),
        )
        val types = mapOf("D" to NodeType.DOOR, "X" to NodeType.DEAD_END)
        val nodes = points.mapValues { (id, p) -> Node(id, p, types[id] ?: NodeType.JUNCTION) }
        val links =
            listOf("L" to "R", "L" to "BL", "BL" to "D", "D" to "BR", "BR" to "R", "R" to "X")
        val edges = links.map { (a, b) ->
            Edge("$a-$b", a, b, emptyList(), points.getValue(a).distanceTo(points.getValue(b)))
        }
        FloorGraph("t", "1", nodes, edges, listOf(Landmark("3418", "D", 1f)), "L")
    }

    private fun reroute(edge: String, offset: Double, heading: Double, penalty: Double = 40.0) =
        router.rerouteToLabel(graph, edge, offset, heading, "3418", penalty)

    @Test
    fun `walking on keeps the way ahead`() {
        // On the top corridor 200 px from L, walking right: ahead via R is 100+100+150 = 350,
        // back via L is 200+100+150 = 450.
        val r = assertNotNull(reroute("L-R", 200.0, right))
        assertFalse(r.turnAround)
        assertEquals(listOf("L", "R", "BR", "D"), r.route.nodeIds)
        assertEquals(listOf("L-R", "BR-R", "D-BR"), r.route.edgeIds)
        assertEquals(350.0, r.costPx, 1e-6)
    }

    @Test
    fun `the route runs through the walker so progress starts where they stand`() {
        val r = assertNotNull(reroute("L-R", 200.0, right))
        // The first leg is the whole corridor L → R; the walker is 200 px along it.
        assertEquals(300.0 + 100.0 + 150.0, r.route.lengthPx, 1e-6)
    }

    @Test
    fun `turning round wins only when it saves more than the penalty`() {
        // 100 px from L walking right: ahead 200+100+150 = 450, back 100+100+150+40 = 390.
        val back = assertNotNull(reroute("L-R", 100.0, right))
        assertTrue(back.turnAround)
        assertEquals(listOf("R", "L", "BL", "D"), back.route.nodeIds)
        // 140 px from L: ahead 410, back 390 + 40 penalty = 430, so keep going.
        assertFalse(assertNotNull(reroute("L-R", 140.0, right)).turnAround)
        // Walking left there instead, the same way round is ahead and needs no U-turn.
        assertFalse(assertNotNull(reroute("L-R", 140.0, left)).turnAround)
    }

    @Test
    fun `a dead end ahead means turning round`() {
        // On the spur toward X: the only way to the room is back through R.
        val r = assertNotNull(reroute("R-X", 50.0, right))
        assertTrue(r.turnAround)
        assertEquals(listOf("X", "R", "BR", "D"), r.route.nodeIds)
    }

    @Test
    fun `standing on the room's own corridor routes straight to the door`() {
        val r = assertNotNull(reroute("BL-D", 100.0, right))
        assertFalse(r.turnAround)
        assertEquals(listOf("BL", "D"), r.route.nodeIds)
        assertEquals(50.0, r.costPx, 1e-6)
    }

    @Test
    fun `unknown room or unreachable target gives no route`() {
        assertNull(router.rerouteToLabel(graph, "L-R", 10.0, right, "9999", 40.0))
    }
}
