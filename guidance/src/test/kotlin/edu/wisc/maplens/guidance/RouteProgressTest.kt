package edu.wisc.maplens.guidance

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.Route
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteProgressTest {
    private val right = 0.0
    private val left = PI
    private val down = PI / 2

    /** k = 10 px/step at 0.7 m: 2.5 m off route is 35.7 px. */
    private val k = 10.0

    /**
     * A U-shaped route S → A → B → C: right 200 px, down 60 px, back left 200 px, so its
     * first and last legs run side by side 60 px (4.2 m) apart. Branch W leaves A upwards.
     */
    private val graph = run {
        val points = mapOf(
            "S" to PixelPoint(0.0, 0.0),
            "A" to PixelPoint(200.0, 0.0),
            "B" to PixelPoint(200.0, 60.0),
            "C" to PixelPoint(0.0, 60.0),
            "W" to PixelPoint(200.0, -150.0),
        )
        val nodes = points.mapValues { (id, p) -> Node(id, p, NodeType.JUNCTION) }
        val edges = listOf("S" to "A", "A" to "B", "B" to "C", "A" to "W").map { (a, b) ->
            Edge("$a$b", a, b, emptyList(), points.getValue(a).distanceTo(points.getValue(b)))
        }
        FloorGraph("t", "1", nodes, edges, emptyList(), "S")
    }
    private val route = Route(listOf("S", "A", "B", "C"), listOf("SA", "AB", "BC"), 460.0)
    private val path = RoutePath(graph, route)

    private fun pose(x: Double, y: Double, heading: Double) =
        PoseEstimate(0L, PixelPoint(x, y), heading, null, 0.0, 1.0, k)

    private fun RouteProgress.walk(vararg poses: PoseEstimate) = poses.map { update(it) }

    @Test
    fun `walking the route tracks progress and never asks for a reroute`() {
        val progress = RouteProgress(path)
        val statuses = (1..19).map { progress.update(pose(it * k, 0.0, right)) } +
            (1..5).map { progress.update(pose(200.0, it * k, down)) } +
            (1..19).map { progress.update(pose(200.0 - it * k, 60.0, left)) }
        assertTrue(statuses.none { it.offRoute || it.facingBack || it.reroute != null })
        assertEquals(450.0, statuses.last().progressPx, 1e-6)
    }

    @Test
    fun `progress stays on the stretch being walked when another passes close by`() {
        val progress = RouteProgress(path)
        // Walking the first leg, 3 px below it: the last leg is 57 px away, closer than 2.5 m
        // would allow, but it is far outside the search window, so progress stays near 50.
        val status = progress.walk(*Array(5) { pose((it + 1) * k, 3.0, right) }).last()
        assertEquals(50.0, status.progressPx, 1e-6)
        // A one-shot global projection would agree here; the case that matters is the last
        // leg's mirror point when standing between the legs, nearer the far one.
        val between = progress.update(pose(60.0, 32.0, right))
        assertEquals(60.0, between.progressPx, 1e-6)
    }

    @Test
    fun `three steps off the line ask for an OFF_ROUTE reroute and hold progress`() {
        val progress = RouteProgress(path)
        progress.walk(*Array(20) { pose((it + 1) * k, 0.0, right) })
        // Up the W branch instead of down to B.
        val up = (1..3).map { progress.update(pose(200.0, -it * 40.0, -down)) }
        assertTrue(up.all { it.offRoute })
        assertNull(up[1].reroute)
        assertEquals(RerouteReason.OFF_ROUTE, up[2].reroute)
        assertTrue(up.all { it.progressPx == 200.0 })
    }

    @Test
    fun `one stray estimate off the line is not a reroute`() {
        val progress = RouteProgress(path)
        val statuses = progress.walk(
            pose(10.0, 0.0, right),
            pose(20.0, 50.0, right),
            pose(30.0, 0.0, right),
            pose(40.0, 0.0, right),
        )
        assertTrue(statuses[1].offRoute)
        assertTrue(statuses.none { it.reroute != null })
    }

    @Test
    fun `turning round shows turn around after two steps without rerouting`() {
        val progress = RouteProgress(path)
        progress.walk(*Array(10) { pose((it + 1) * k, 0.0, right) })
        val first = progress.update(pose(100.0, 0.0, left))
        val second = progress.update(pose(100.0, 0.0, left))
        assertFalse(first.facingBack)
        assertTrue(second.facingBack)
        assertNull(second.reroute)
        // Turning back the right way clears it at once.
        assertFalse(progress.update(pose(110.0, 0.0, right)).facingBack)
    }

    @Test
    fun `walking back along the route asks for a WRONG_WAY reroute`() {
        val progress = RouteProgress(path)
        progress.walk(*Array(15) { pose((it + 1) * k, 0.0, right) })
        val back = (1..5).map { progress.update(pose(150.0 - it * k, 0.0, left)) }
        assertTrue(back.take(4).none { it.reroute != null })
        assertEquals(RerouteReason.WRONG_WAY, back.last().reroute)
    }

    @Test
    fun `turning on the spot for many steps is not a wrong way`() {
        val progress = RouteProgress(path)
        progress.walk(*Array(15) { pose((it + 1) * k, 0.0, right) })
        val spot = (1..8).map { progress.update(pose(150.0, 0.0, left)) }
        assertTrue(spot.drop(1).all { it.facingBack })
        assertTrue(spot.none { it.reroute != null })
    }

    @Test
    fun `a cooldown holds back the next reroute`() {
        val progress = RouteProgress(path, startPx = 200.0, cooldownSteps = 4)
        val up = (1..4).map { progress.update(pose(200.0, -it * 40.0, -down)) }
        assertTrue(up.all { it.offRoute })
        assertTrue(up.none { it.reroute != null })
        assertEquals(RerouteReason.OFF_ROUTE, progress.update(pose(200.0, -200.0, -down)).reroute)
    }

    @Test
    fun `a new route that starts behind the walker says turn around straight away`() {
        val progress = RouteProgress(path, startPx = 50.0, startFacingBack = true)
        assertTrue(progress.facingBack)
        assertFalse(progress.update(pose(60.0, 0.0, right)).facingBack)
    }

    @Test
    fun `the walked part follows the drawn dot but only within a step of the tracker`() {
        val progress = RouteProgress(path)
        progress.walk(*Array(10) { pose((it + 1) * k, 0.0, right) })
        assertEquals(104.0, progress.displayPx(PixelPoint(104.0, 0.0), k), 1e-6)
        // A dot drawn far ahead (or on the last leg) can't drag it along.
        assertEquals(110.0, progress.displayPx(PixelPoint(180.0, 0.0), k), 1e-6)
        assertEquals(90.0, progress.displayPx(PixelPoint(20.0, 60.0), k), 1e-6)
    }

    @Test
    fun `instruction from a tracked progress matches the pose based one`() {
        val guide = RouteGuide(graph, route)
        val p = pose(120.0, 0.0, right)
        assertEquals(guide.instruction(p), guide.instruction(120.0, k))
        assertEquals(guide.remainingSteps(p), guide.remainingSteps(120.0, k))
    }
}
