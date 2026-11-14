package edu.wisc.maplens.nav

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
