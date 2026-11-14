package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.corridorsAt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosterStraightenTest {
    private fun p(x: Double, y: Double) = PixelPoint(x, y)

    private fun graph(nodes: List<Node>, edges: List<Edge>, start: String = nodes.first().id) =
        FloorGraph("t", "3", nodes.associateBy { it.id }, edges, emptyList(), start)

    private fun edge(id: String, a: Node, b: Node, vararg inside: PixelPoint): Edge {
        val line = listOf(a.position) + inside.toList() + b.position
        return Edge(id, a.id, b.id, line, pathLength(line))
    }

    /** Nov 12 EH3 scan: the trace bends up into the star at (1092, 1007) on a y = 1020 hall. */
    private val star = p(1092.0, 1004.0)
    private val w = Node("W", p(700.0, 1020.0), NodeType.DEAD_END)
    private val k = Node("K", p(1130.0, 1019.0), NodeType.JUNCTION)
    private val e = Node("E", p(1500.0, 1021.0), NodeType.DEAD_END)
    private val up = Node("U", p(1130.0, 700.0), NodeType.DEAD_END)

    /** Corridor W–K–E bending into the star, and a crooked side corridor K–U. */
    private val scanned = graph(
        listOf(w, k, e, up),
        listOf(
            edge("a", w, k, p(1069.0, 1021.0), p(1092.0, 1007.0), p(1109.0, 1018.0)),
            edge("c", k, e),
            edge("side", k, up, p(1150.0, 985.0), p(1130.0, 900.0)),
        ),
    )

    /** EH3 by 3425: the y = 1214 hall bumps 16 px up toward the stair exit and back. */
    @Test
    fun `a bump in a straight corridor is flattened, a corner is kept`() {
        val a = Node("A", p(1428.0, 1207.0), NodeType.JUNCTION)
        val b = Node("B", p(924.0, 1214.0), NodeType.JUNCTION)
        val c = Node("C", p(924.0, 600.0), NodeType.DEAD_END)
        val g = graph(
            listOf(a, b, c),
            listOf(
                edge(
                    "hall",
                    a,
                    b,
                    p(1366.0, 1219.0),
                    p(1074.0, 1214.0),
                    p(1015.0, 1198.0),
                    p(990.0, 1213.0)
                ),
                edge("corner", a, c, p(1428.0, 600.0)),
            ),
        ).withoutBumps()
        assertEquals(listOf(a.position, b.position), g.edges.single { it.id == "hall" }.polyline)
        assertEquals(3, g.edges.single { it.id == "corner" }.polyline.size)
        assertTrue(g.validate().isEmpty())
    }

    @Test
    fun `the bend into the star is pulled onto the corridor line`() {
        val g = scanned.straightenedAround(star)
        val line = g.edges.single { it.id == "a" }.polyline
        assertTrue(line.all { abs(it.y - 1020.0) < 1.5 }, "on the corridor: $line")
        // START then attaches on the line, and leaves it straight both ways.
        val started = resolveStart(g, listOf(star), mutableListOf())
        val bearings = started.corridorsAt(started.startNodeId)
            .map { abs(Math.toDegrees(it.bearingRad)) }.sorted()
        assertEquals(0.0, bearings[0], 1.0)
        assertEquals(180.0, bearings[1], 1.0)
        assertTrue(g.validate().isEmpty())
    }

    @Test
    fun `nothing away from the star changes`() {
        val g = scanned.straightenedAround(star)
        assertEquals(scanned.nodes, g.nodes)
        for (id in listOf("c", "side")) {
            assertEquals(scanned.edges.single { it.id == id }, g.edges.single { it.id == id }, id)
        }
    }

    @Test
    fun `a star at a corner is left as traced`() {
        val a = Node("A", p(0.0, 0.0), NodeType.DEAD_END)
        val corner = Node("C", p(300.0, 0.0), NodeType.JUNCTION)
        val b = Node("B", p(300.0, 300.0), NodeType.DEAD_END)
        val g = graph(
            listOf(a, corner, b),
            listOf(edge("x", a, corner, p(280.0, 5.0)), edge("y", corner, b)),
        )
        assertEquals(g, g.straightenedAround(p(300.0, 0.0)))
    }

    @Test
    fun `features join the corridor, reuse ends and skip the legend`() {
        val a = Node("A", p(0.0, 0.0), NodeType.DEAD_END)
        val d = Node("D", p(200.0, 0.0), NodeType.DOOR)
        val b = Node("B", p(400.0, 0.0), NodeType.DEAD_END)
        val base = graph(listOf(a, d, b), listOf(edge("e1", a, d), edge("e2", d, b)))
            .copy(landmarks = listOf(Landmark("3540", "D", 1f)))
        // An exit sign beside the corridor end, two lift doors (one elevator), an AED beside
        // a door (on a stub) and the legend's AED icon off the plan.
        val g = base.attachFeatures(
            listOf(
                PosterFeature(NodeType.EXIT, p(410.0, -40.0)),
                PosterFeature(NodeType.ELEVATOR, p(100.0, -50.0)),
                PosterFeature(NodeType.ELEVATOR, p(160.0, -50.0)),
                PosterFeature(NodeType.AED, p(200.0, -60.0)),
                PosterFeature(NodeType.AED, p(900.0, 600.0)),
            ),
        )
        assertEquals(NodeType.EXIT, g.nodes.getValue("B").type)
        val lifts = g.nodes.values.filter { it.type == NodeType.ELEVATOR }
        assertEquals(listOf(p(130.0, 0.0)), lifts.map { it.position })
        val aed = g.nodes.values.single { it.type == NodeType.AED }
        assertEquals(p(200.0, -30.0), aed.position)
        assertEquals(NodeType.DOOR, g.nodes.getValue("D").type)
        assertEquals(base.landmarks, g.landmarks)
        assertTrue(g.copy(startNodeId = "A").validate().isEmpty())
    }
}
