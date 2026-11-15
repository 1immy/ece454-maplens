package edu.wisc.maplens.nav

import edu.wisc.maplens.crossings
import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmoothCorridorsTest {
    private fun p(x: Double, y: Double) = PixelPoint(x, y)

    private fun graph(vararg line: PixelPoint): FloorGraph {
        val a = Node("A", line.first(), NodeType.START)
        val b = Node("B", line.last(), NodeType.DOOR)
        val length = line.toList().zipWithNext { u, v -> u.distanceTo(v) }.sum()
        return FloorGraph(
            "t",
            "3",
            mapOf("A" to a, "B" to b),
            listOf(Edge("e", "A", "B", line.toList(), length)),
            emptyList(),
            "A"
        )
    }

    @Test
    fun `a wiggly corridor becomes its straight line, ends untouched`() {
        val g = graph(p(0.0, 0.0), p(50.0, 6.0), p(100.0, -4.0), p(150.0, 5.0), p(200.0, 0.0))
        val smooth = g.smoothedCorridors()
        assertEquals(listOf(p(0.0, 0.0), p(200.0, 0.0)), smooth.edges.single().polyline)
        assertEquals(200.0, smooth.edges.single().lengthPx, 1e-9)
        assertEquals(g.nodes, smooth.nodes)
    }

    @Test
    fun `a real corner stays`() {
        val g = graph(p(0.0, 0.0), p(100.0, 3.0), p(100.0, 100.0))
        assertEquals(
            listOf(p(0.0, 0.0), p(100.0, 3.0), p(100.0, 100.0)),
            g.smoothedCorridors().edges.single().polyline,
        )
    }

    /** Nov 13 scan: straightening a corridor would cut across a short door link beside it. */
    @Test
    fun `a corridor keeps its trace where its straight line would cross another`() {
        val a = Node("A", p(0.0, 0.0), NodeType.JUNCTION)
        val b = Node("B", p(200.0, 0.0), NodeType.JUNCTION)
        val c = Node("C", p(100.0, 4.0), NodeType.JUNCTION)
        val d = Node("D", p(100.0, -3.0), NodeType.DOOR)
        val bowed = listOf(p(0.0, 0.0), p(100.0, 8.0), p(200.0, 0.0))
        val g = FloorGraph(
            "t",
            "3",
            listOf(a, b, c, d).associateBy { it.id },
            listOf(
                Edge("hall", "A", "B", bowed, bowed.zipWithNext { u, v -> u.distanceTo(v) }.sum()),
                Edge("link", "C", "D", listOf(c.position, d.position), 7.0),
            ),
            emptyList(),
            "A",
        )
        val smooth = g.smoothedCorridors()
        assertEquals(bowed, smooth.edges.single { it.id == "hall" }.polyline)
        assertTrue(smooth.crossings().isEmpty())
    }
}
