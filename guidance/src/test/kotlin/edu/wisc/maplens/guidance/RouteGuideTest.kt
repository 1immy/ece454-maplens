package edu.wisc.maplens.guidance

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.Route
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteGuideTest {
    /**
     * y-down image. S (0,0) -> J (100,0) is walking right. From J: D (100,100) is down the
     * screen (a right turn), U (100,-100) is up (a left turn). B is reached by a bent edge
     * that turns 45° right halfway, and its polyline is stored from B back to J.
     */
    private val graph = run {
        val nodes = listOf(
            Node("S", PixelPoint(0.0, 0.0), NodeType.START),
            Node("J", PixelPoint(100.0, 0.0), NodeType.JUNCTION),
            Node("D", PixelPoint(100.0, 100.0), NodeType.DOOR),
            Node("U", PixelPoint(100.0, -100.0), NodeType.DOOR),
            Node("B", PixelPoint(250.0, 50.0), NodeType.DEAD_END),
        ).associateBy { it.id }
        fun e(id: String, a: String, b: String) = Edge(
            id,
            a,
            b,
            listOf(nodes.getValue(a).position, nodes.getValue(b).position),
            nodes.getValue(a).position.distanceTo(nodes.getValue(b).position),
        )
        val bend = listOf(PixelPoint(250.0, 50.0), PixelPoint(200.0, 0.0), PixelPoint(100.0, 0.0))
        FloorGraph(
            buildingId = "t",
            floor = "1",
            nodes = nodes,
            edges = listOf(
                e("SJ", "S", "J"),
                e("JD", "J", "D"),
                e("JU", "J", "U"),
                Edge("BJ", "B", "J", bend, 100.0 + 50.0 * sqrt(2.0)),
            ),
            landmarks = listOf(Landmark("3546", "D", 1f), Landmark("3650", "U", 1f)),
            startNodeId = "S",
        )
    }

    private val toD = Route(listOf("S", "J", "D"), listOf("SJ", "JD"), 200.0)

    private fun pose(x: Double, y: Double, k: Double = 25.0) =
        PoseEstimate(0L, PixelPoint(x, y), 0.0, null, 0.0, 1.0, k)

    @Test
    fun `right and left turns follow the y-down image convention`() {
        val right = RouteGuide(graph, toD).cues
        assertEquals(listOf(Maneuver.TURN_RIGHT, Maneuver.ARRIVE), right.map { it.maneuver })
        assertEquals(100.0, right.first().distancePx, 1e-9)
        assertEquals("J", right.first().nodeId)
        val left = RouteGuide(graph, Route(listOf("S", "J", "U"), listOf("SJ", "JU"), 200.0))
        assertEquals(Maneuver.TURN_LEFT, left.cues.first().maneuver)
    }

    @Test
    fun `a bend inside a reversed edge is a slight turn with no node`() {
        val guide = RouteGuide(graph, Route(listOf("S", "J", "B"), listOf("SJ", "BJ"), 270.7))
        val tail = guide.path.points.drop(2)
        assertEquals(listOf(PixelPoint(200.0, 0.0), PixelPoint(250.0, 50.0)), tail)
        val bend = guide.cues.first()
        assertEquals(Maneuver.SLIGHT_RIGHT, bend.maneuver)
        assertNull(bend.nodeId)
        assertEquals(200.0, bend.distancePx, 1e-9)
        val instruction = guide.instruction(pose(150.0, 0.0))
        assertEquals("where the corridor bends", instruction.detail)
        assertEquals(2, instruction.stepsUntil)
    }

    @Test
    fun `straight-through junctions and small wiggles give no cue`() {
        val wiggle = graph.copy(
            nodes = graph.nodes + ("W" to Node("W", PixelPoint(200.0, 10.0), NodeType.DOOR)),
            edges = graph.edges + Edge(
                "JW",
                "J",
                "W",
                listOf(PixelPoint(100.0, 0.0), PixelPoint(200.0, 10.0)),
                100.5,
            ),
        )
        val guide = RouteGuide(wiggle, Route(listOf("S", "J", "W"), listOf("SJ", "JW"), 200.5))
        assertEquals(listOf(Maneuver.ARRIVE), guide.cues.map { it.maneuver })
    }

    @Test
    fun `countdown to the next maneuver, then arrival`() {
        val guide = RouteGuide(graph, toD)
        val turn = Instruction(Maneuver.TURN_RIGHT, 4, "at the junction")
        assertEquals(turn, guide.instruction(pose(0.0, 0.0)))
        assertEquals(2, guide.instruction(pose(60.0, 3.0)).stepsUntil)
        val door = Instruction(Maneuver.ARRIVE, 3, "at room 3546")
        assertEquals(door, guide.instruction(pose(100.0, 30.0)))
        assertEquals(door.copy(stepsUntil = 0), guide.instruction(pose(100.0, 80.0)))
        assertEquals(8, guide.remainingSteps(pose(0.0, 0.0)))
    }

    @Test
    fun `a larger k means fewer steps`() {
        val guide = RouteGuide(graph, toD)
        assertEquals(2, guide.instruction(pose(0.0, 0.0, k = 50.0)).stepsUntil)
    }

    @Test
    fun `a one-node route is an immediate arrival`() {
        val guide = RouteGuide(graph, Route(listOf("D"), emptyList(), 0.0))
        assertEquals(listOf(Maneuver.ARRIVE), guide.cues.map { it.maneuver })
        assertEquals(0, guide.instruction(pose(100.0, 100.0)).stepsUntil)
    }

    @Test
    fun `headlines read naturally`() {
        assertEquals("Turn left in 12 steps", Instruction(Maneuver.TURN_LEFT, 12, "").headline())
        assertEquals("Bear right in 1 step", Instruction(Maneuver.SLIGHT_RIGHT, 1, "").headline())
        assertEquals("You have arrived", Instruction(Maneuver.ARRIVE, 0, "").headline())
        assertEquals("Arrive in 3 steps", Instruction(Maneuver.ARRIVE, 3, "").headline())
    }

    @Test
    fun `fake pose stream walks the route to the door`() {
        val guide = RouteGuide(graph, toD)
        val walk = FakePoseStream(guide.path, stepLengthPx = 25.0)
        val start = walk.current()
        assertEquals(PixelPoint(0.0, 0.0), start.position)
        assertEquals(0.0, start.headingRad, 1e-9)
        assertEquals("SJ", start.edgeId)
        val poses = generateSequence { if (walk.finished) null else walk.step() }.toList()
        assertEquals(8, poses.size)
        assertEquals(PixelPoint(100.0, 25.0), poses[4].position)
        assertEquals(PI / 2, poses[4].headingRad, 1e-9)
        assertEquals("JD", poses[4].edgeId)
        assertEquals(PixelPoint(100.0, 100.0), poses.last().position)
        assertEquals(Maneuver.ARRIVE, guide.instruction(poses.last()).maneuver)
        assertEquals(0, guide.instruction(poses.last()).stepsUntil)
        // The countdown never goes up while walking toward a cue.
        val steps = poses.map { guide.instruction(it) }
            .takeWhile { it.maneuver == Maneuver.TURN_RIGHT }
        assertTrue(steps.zipWithNext().all { (a, b) -> b.stepsUntil < a.stepsUntil })
        assertEquals(PixelPoint(100.0, 100.0), walk.step().position)
    }
}
