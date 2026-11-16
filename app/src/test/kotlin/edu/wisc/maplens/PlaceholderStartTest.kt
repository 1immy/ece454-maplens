package edu.wisc.maplens

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceholderStartTest {
    /** Door A - junction J1 - junction J2 - door B along one corridor; no START (no star). */
    private fun starless(extra: List<Node> = emptyList()): FloorGraph {
        val nodes = listOf(
            Node("A", PixelPoint(0.0, 0.0), NodeType.DOOR),
            Node("J1", PixelPoint(90.0, 0.0), NodeType.JUNCTION),
            Node("J2", PixelPoint(210.0, 0.0), NodeType.JUNCTION),
            Node("B", PixelPoint(300.0, 0.0), NodeType.DOOR),
        ) + extra
        val edges = listOf("A" to "J1", "J1" to "J2", "J2" to "B").mapIndexed { i, (a, b) ->
            val pa = nodes.single { it.id == a }.position
            val pb = nodes.single { it.id == b }.position
            Edge("e$i", a, b, listOf(pa, pb), pa.distanceTo(pb))
        }
        return FloorGraph(
            "b",
            "3",
            nodes.associateBy { it.id },
            edges,
            listOf(Landmark("3418", "A", 0.9f), Landmark("3420", "B", 0.9f)),
            "",
        )
    }

    @Test
    fun startGoesOnTheJunctionNearestTheMiddleNeverADoor() {
        val placed = checkNotNull(starless().withPlaceholderStart())
        assertEquals("J1", placed.startNodeId)
        assertEquals(NodeType.START, placed.nodes.getValue("J1").type)
        assertEquals(NodeType.DOOR, placed.nodes.getValue("A").type)
        assertTrue(mapProblems(placed).none { it.blocksSave })
    }

    @Test
    fun aMapWithOtherProblemsStillBlocks() {
        // A point nothing links to stays cut off, wherever START goes.
        val island = Node("X", PixelPoint(150.0, 400.0), NodeType.DEAD_END)
        val placed = checkNotNull(starless(listOf(island)).withPlaceholderStart())
        assertTrue(mapProblems(placed).any { it.blocksSave })
    }
}
