package edu.wisc.maplens.live

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.StepSource
import edu.wisc.maplens.tracking.ParticleTracker
import java.io.File
import java.io.StringWriter
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTrackingTest {
    /**
     * y-down image. L (stairs) - S (start) - D1 "3418" - J, all along y = 0. From J: U (dead
     * end) is up, E (elevator) is down, R "3420" is right.
     */
    private val graph = run {
        val nodes = listOf(
            Node("L", PixelPoint(-100.0, 0.0), NodeType.STAIRS),
            Node("S", PixelPoint(0.0, 0.0), NodeType.START),
            Node("D1", PixelPoint(100.0, 0.0), NodeType.DOOR),
            Node("J", PixelPoint(200.0, 0.0), NodeType.JUNCTION),
            Node("U", PixelPoint(200.0, -100.0), NodeType.DEAD_END),
            Node("E", PixelPoint(200.0, 100.0), NodeType.ELEVATOR),
            Node("R", PixelPoint(300.0, 0.0), NodeType.DOOR),
        ).associateBy { it.id }
        fun e(a: String, b: String): Edge {
            val pa = nodes.getValue(a).position
            val pb = nodes.getValue(b).position
            return Edge("$a-$b", a, b, listOf(pa, pb), pa.distanceTo(pb))
        }
        FloorGraph(
            buildingId = "t",
            floor = "1",
            nodes = nodes,
            edges = listOf(
                e("L", "S"),
                e("S", "D1"),
                e("D1", "J"),
                e("J", "U"),
                e("J", "E"),
                e("R", "J"),
            ),
            landmarks = listOf(Landmark("3418", "D1", 1f), Landmark("3420", "R", 1f)),
            startNodeId = "S",
        )
    }

    private var clock = 0L

    private fun step(heading: Double) = MotionEvent.Step(++clock, heading, StepSource.STEP_DETECTOR)

    private fun pose(x: Double, y: Double, edgeId: String) =
        PoseEstimate(clock, PixelPoint(x, y), 0.0, edgeId, 0.0, 1.0, 25.0)

    /** The live path end to end, minus sensors: START facing → ParticleTracker → checkpoint. */
    @Test
    fun `particle tracker from the START facing option stays on the walked corridor`() {
        val facing = graph.facingOptions("S").single { it.arrow == "→" }
        val tracker = ParticleTracker(random = Random(454))
        tracker.start(graph, "S", facing.bearingRad, stepLengthPx = 25.0)
        var estimate = tracker.pose
        repeat(6) { estimate = tracker.onMotion(step(0.0)) }
        val result = graph.checkpoint("C1", estimate, PixelPoint(150.0, 0.0), strideMeters = 0.7)
        assertTrue(result.sameCorridor, "estimate ${estimate.position} on ${estimate.edgeId}")
        assertTrue(result.errorPx < 50.0, "error ${result.errorPx} px")
    }

    @Test
    fun `arrows follow the y-down map`() {
        assertEquals("→", arrowFor(0.0))
        assertEquals("↓", arrowFor(PI / 2))
        assertEquals("↑", arrowFor(-PI / 2))
        assertEquals("←", arrowFor(PI))
        assertEquals("←", arrowFor(-PI + 0.01))
        assertEquals("↙", arrowFor(3 * PI / 4))
    }

    @Test
    fun `facing options name what lies along each corridor`() {
        val atStart = graph.facingOptions("S").associate { it.arrow to it.description }
        assertEquals(mapOf("→" to "past room 3418", "←" to "toward the stairs"), atStart)
        val atJunction = graph.facingOptions("J").associate { it.arrow to it.description }
        assertEquals("along the corridor", atJunction["↑"])
        assertEquals("toward the elevator", atJunction["↓"])
        assertEquals("past room 3420", atJunction["→"])
        assertEquals("past room 3418", atJunction["←"])
    }

    @Test
    fun `corridors join edges through doors but split at junctions`() {
        val ids = graph.corridorIds()
        assertEquals(ids["L-S"], ids["S-D1"])
        assertEquals(ids["S-D1"], ids["D1-J"])
        assertEquals(4, ids.values.toSet().size)
    }

    @Test
    fun `checkpoint compares the estimate with the tapped truth`() {
        val estimate = pose(150.0, 0.0, "D1-J")
        val near = graph.checkpoint("C1", estimate, PixelPoint(60.0, 5.0), strideMeters = 0.7)
        assertEquals("S-D1", near.truthEdgeId)
        assertTrue(near.sameCorridor)
        assertEquals(90.139, near.errorPx, 1e-3)
        assertEquals(90.139 / 25 * 0.7, near.errorMeters, 1e-3)
        val wrong = graph.checkpoint("C2", estimate, PixelPoint(205.0, 60.0), strideMeters = 0.7)
        assertEquals("J-E", wrong.truthEdgeId)
        assertFalse(wrong.sameCorridor)
    }

    @Test
    fun `session log writes one row per event under a fixed header`() {
        val out = StringWriter()
        val log = SessionLog(out)
        log.start(0L, "live", "3418", 0.0, 25.0)
        val pose = pose(25.0, 0.0, "S-D1")
        log.pose(pose)
        log.checkpoint(5L, graph.checkpoint("C1", pose, PixelPoint(25.0, 3.0), 0.7))
        log.arrive(pose)
        val lines = out.toString().trim().lines()
        assertEquals(SessionLog.HEADER, lines.first())
        val events = lines.drop(1).map { it.split(",")[1] }
        assertEquals(listOf("start", "pose", "checkpoint", "arrive"), events)
        val columns = SessionLog.HEADER.split(",").size
        assertTrue(lines.all { it.split(",").size == columns })
        val checkpoint = lines[3].split(",")
        assertEquals("C1", checkpoint[2])
        assertEquals("S-D1", checkpoint[9])
        assertEquals("3.000", checkpoint[10])
        assertEquals("true", checkpoint[12])
    }

    /** On the traced EH3 graph, every START corridor gets a readable description. */
    @Test
    fun `EH3 START offers the elevator and both corridors`() {
        val file = listOf("../data/fixtures/maps", "data/fixtures/maps")
            .map { File(it, "eh-3-elevators-evac.graph.json") }
            .firstOrNull { it.exists() } ?: return
        val eh3 = FloorGraphJson.decode(file.readText())
        val options = eh3.facingOptions(eh3.startNodeId)
        assertEquals(3, options.size)
        assertTrue(options.any { it.description == "toward the elevator" && it.arrow == "↓" })
        assertTrue(options.all { it.description.isNotBlank() })
        assertEquals(eh3.edges.size, eh3.corridorIds().size)
    }
}
