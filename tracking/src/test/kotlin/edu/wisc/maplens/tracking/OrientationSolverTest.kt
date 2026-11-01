package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.tracking.OrientObservation.Blocked
import edu.wisc.maplens.tracking.OrientObservation.CorridorAxis
import edu.wisc.maplens.tracking.OrientObservation.Door
import edu.wisc.maplens.tracking.OrientObservation.Opening
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Synthetic scans: each observation is a map bearing seen through a known true offset. */
class OrientationSolverTest {
    private val solver = OrientationSolver()

    /** Junction J at the origin with arms; map bearing 0° is right, 90° is down. */
    private fun junction(
        vararg arms: Pair<String, PixelPoint>,
        doors: Map<String, String> = emptyMap(),
        bends: Map<String, List<PixelPoint>> = emptyMap(),
    ): FloorGraph {
        val nodes = mapOf("J" to Node("J", PixelPoint(0.0, 0.0), NodeType.JUNCTION)) +
            arms.associate { (id, p) -> id to Node(id, p, NodeType.DOOR) }
        val edges = arms.map { (id, p) ->
            val line = listOf(PixelPoint(0.0, 0.0)) + bends[id].orEmpty() + p
            Edge("J-$id", "J", id, line, line.zipWithNext { a, b -> a.distanceTo(b) }.sum())
        }
        val landmarks = doors.map { (label, node) -> Landmark(label, node, 1f) }
        return FloorGraph("test", "1", nodes, edges, landmarks, "J")
    }

    private val east = "E" to PixelPoint(100.0, 0.0)
    private val south = "S" to PixelPoint(0.0, 100.0)
    private val west = "W" to PixelPoint(-100.0, 0.0)
    private val north = "N" to PixelPoint(0.0, -100.0)

    private fun seen(mapDeg: Double, trueOffsetDeg: Double) =
        Math.toRadians(mapDeg - trueOffsetDeg)

    private fun assertOffset(expectedDeg: Double, actualRad: Double, toleranceDeg: Double = 1.0) {
        val diff = (Math.toDegrees(actualRad) - expectedDeg).mod(360.0).let { minOf(it, 360 - it) }
        assertTrue(
            diff <= toleranceDeg,
            "expected $expectedDeg°, got ${Math.toDegrees(actualRad)}°"
        )
    }

    @Test
    fun `one recognized door fixes the offset`() {
        val graph = junction(east, south, west, doors = mapOf("3445" to "S"))
        val result = solver.solve(graph, "J", listOf(Door("3445", seen(90.0, 37.0))))
        assertEquals(OrientStatus.CLEAR, result.status)
        assertOffset(37.0, result.best!!.offsetRad)
    }

    @Test
    fun `labels match case-insensitively with surrounding spaces`() {
        val graph = junction(east, south, doors = mapOf("3539A" to "E"))
        val result = solver.solve(graph, "J", listOf(Door(" 3539a ", seen(0.0, -50.0))))
        assertOffset(-50.0, result.best!!.offsetRad)
    }

    @Test
    fun `a corridor axis in a straight corridor leaves a 180 degree tie`() {
        val graph = junction(east, west)
        val result = solver.solve(graph, "J", listOf(CorridorAxis(seen(0.0, 20.0))))
        assertEquals(OrientStatus.AMBIGUOUS, result.status)
        assertEquals(2, result.candidates.size)
        assertOffset(
            20.0,
            result.candidates.minBy { abs(it.offsetRad - Math.toRadians(20.0)) }.offsetRad
        )
        assertTrue(result.candidates.any { abs(abs(it.offsetRad) - Math.toRadians(160.0)) < 0.02 })
    }

    @Test
    fun `openings and a wall at a T junction pick one offset`() {
        val graph = junction(east, south, west)
        val offset = -112.0
        val scan = listOf(
            Opening(seen(0.0, offset)),
            Opening(seen(90.0, offset)),
            Opening(seen(180.0, offset)),
            Blocked(seen(-90.0, offset)),
        )
        val result = solver.solve(graph, "J", scan)
        assertEquals(OrientStatus.CLEAR, result.status)
        assertOffset(offset, result.best!!.offsetRad)
    }

    @Test
    fun `a symmetric cross junction stays four-way ambiguous`() {
        val graph = junction(east, south, west, north)
        val scan = listOf(0.0, 90.0, 180.0, 270.0).map { Opening(seen(it, 10.0)) }
        val result = solver.solve(graph, "J", scan)
        assertEquals(OrientStatus.AMBIGUOUS, result.status)
        assertEquals(4, result.candidates.size)
        listOf(10.0, 100.0, 190.0, 280.0).forEach { expected ->
            assertTrue(
                result.candidates.any { candidate ->
                    val diff = (Math.toDegrees(candidate.offsetRad) - expected).mod(360.0)
                    minOf(diff, 360 - diff) <= 1.0
                }
            )
        }
    }

    @Test
    fun `adding a readable door breaks the cross junction tie`() {
        val graph = junction(east, south, west, north, doors = mapOf("3611" to "N"))
        val scan = listOf(0.0, 90.0, 180.0, 270.0).map { Opening(seen(it, 10.0)) } +
            Door("3611", seen(-90.0, 10.0))
        val result = solver.solve(graph, "J", scan)
        assertEquals(OrientStatus.CLEAR, result.status)
        assertOffset(10.0, result.best!!.offsetRad)
    }

    @Test
    fun `one misread door is outvoted`() {
        val graph = junction(
            east,
            south,
            west,
            north,
            doors = mapOf("3611" to "N", "3613" to "E", "3615" to "S", "3617" to "W"),
        )
        val offset = 64.0
        val scan = listOf(
            Door("3611", seen(-90.0, offset)),
            Door("3613", seen(0.0, offset)),
            Door("3615", seen(90.0, offset)),
            // OCR read 3617 on the east door: points the wrong way.
            Door("3617", seen(0.0, offset)),
        )
        val result = solver.solve(graph, "J", scan)
        assertEquals(OrientStatus.CLEAR, result.status)
        assertOffset(offset, result.best!!.offsetRad)
    }

    @Test
    fun `offsets near plus or minus 180 degrees wrap correctly`() {
        val graph = junction(east, south, doors = mapOf("3445" to "E"))
        val result = solver.solve(graph, "J", listOf(Door("3445", seen(0.0, 179.6))))
        assertOffset(179.6, result.best!!.offsetRad)
    }

    @Test
    fun `corridor direction comes from the first bend, not the far end`() {
        // Leaves J heading down, then turns right towards (200, 50).
        val graph = junction(
            "D" to PixelPoint(200.0, 50.0),
            bends = mapOf("D" to listOf(PixelPoint(0.0, 50.0))),
        )
        val result = solver.solve(graph, "J", listOf(Opening(seen(90.0, 5.0))))
        assertEquals(OrientStatus.CLEAR, result.status)
        assertOffset(5.0, result.best!!.offsetRad)
    }

    @Test
    fun `no usable evidence is reported, not guessed`() {
        val graph = junction(east, south, doors = mapOf("3445" to "J"))
        listOf(
            emptyList(),
            listOf(Door("9999", 0.0)),
            // Standing at the door itself gives no bearing to it.
            listOf(Door("3445", 0.0)),
        ).forEach { scan ->
            val result = solver.solve(graph, "J", scan)
            assertEquals(OrientStatus.NO_EVIDENCE, result.status)
            assertEquals(null, result.best)
        }
    }

    @Test
    fun `unknown node is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            solver.solve(junction(east), "nowhere", listOf(Opening(0.0)))
        }
    }
}
