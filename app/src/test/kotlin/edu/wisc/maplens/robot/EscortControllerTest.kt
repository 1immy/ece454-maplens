package edu.wisc.maplens.robot

import edu.wisc.maplens.guidance.RoutePath
import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.Route
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EscortControllerTest {
    /** 25 px per metre: a 400 px corridor right, then 300 px down. */
    private val m = 25.0
    private val path = run {
        val pts = mapOf(
            "S" to PixelPoint(0.0, 0.0),
            "J" to PixelPoint(400.0, 0.0),
            "E" to PixelPoint(400.0, 300.0),
        )
        val nodes = pts.mapValues { (id, p) -> Node(id, p, NodeType.JUNCTION) }
        val edges =
            listOf(
                Edge("SJ", "S", "J", emptyList(), 400.0),
                Edge("JE", "J", "E", emptyList(), 300.0)
            )
        RoutePath(
            FloorGraph("t", "1", nodes, edges, emptyList(), "S"),
            Route(listOf("S", "J", "E"), listOf("SJ", "JE"), 700.0)
        )
    }

    private fun at(s: Double) = path.pointAt(s)

    private fun hand(doors: List<Double> = emptyList()) =
        EscortController(path, doors, listOf(400.0), Carry.HAND, m)

    private fun List<EscortAction>.moves() =
        filterIsInstance<EscortAction.MoveTo>().map { it.alongPx }

    private fun List<EscortAction>.says() = filterIsInstance<EscortAction.Say>().map { it.cue }

    @Test
    fun `in hand temi says follow me and keeps three metres ahead of the walker`() {
        val e = hand()
        val first = e.update(EscortInput(0.0, at(0.0), 0.0, at(0.0)))
        assertEquals(listOf(EscortCue.FOLLOW), first.says())
        assertEquals(listOf(75.0), first.moves())
        // The walker moves 4 m on: temi's target moves with them.
        assertEquals(
            listOf(175.0),
            e.update(EscortInput(3.0, at(75.0), 100.0, at(100.0), 3.0)).moves()
        )
        // A small step doesn't resend a go-to.
        assertTrue(e.update(EscortInput(3.5, at(170.0), 110.0, at(110.0), 3.5)).moves().isEmpty())
        assertEquals(EscortState.LEADING, e.state)
    }

    @Test
    fun `when the walker stops temi stops, faces them and waits, then leads on`() {
        val e = hand()
        e.update(EscortInput(0.0, at(0.0), 0.0, at(0.0), 0.0))
        e.update(EscortInput(1.0, at(150.0), 100.0, at(100.0), 1.0))
        // No step for 5 s, temi 2 m ahead.
        val wait = e.update(EscortInput(6.0, at(150.0), 100.0, at(100.0), 1.0))
        assertEquals(EscortState.WAITING_FOR_USER, e.state)
        assertTrue(EscortAction.Stop in wait)
        // Facing the walker, who is behind it along the corridor (bearing π).
        assertEquals(PI, wait.filterIsInstance<EscortAction.Face>().single().bearing, 1e-9)
        assertEquals(listOf(EscortCue.WAITING), wait.says())
        // Still stopped: nothing more.
        assertTrue(e.update(EscortInput(8.0, at(150.0), 100.0, at(100.0), 1.0)).isEmpty())
        // Walking again: lead on.
        val go = e.update(EscortInput(9.0, at(150.0), 110.0, at(110.0), 9.0))
        assertEquals(listOf(EscortCue.FOLLOW), go.says())
        assertEquals(listOf(185.0), go.moves())
    }

    @Test
    fun `temi never runs more than six metres ahead`() {
        val e = hand()
        e.update(EscortInput(0.0, at(0.0), 0.0, at(0.0), 0.0))
        val far = e.update(EscortInput(1.0, at(260.0), 100.0, at(100.0), 1.0))
        assertEquals(EscortState.WAITING_FOR_USER, e.state)
        assertTrue(EscortAction.Stop in far)
    }

    @Test
    fun `temi stops before a door, asks for help, and goes on once it is open`() {
        val e = hand(doors = listOf(300.0))
        e.update(EscortInput(0.0, at(0.0), 0.0, at(0.0), 0.0))
        // Target is capped at the door stop (300 − 1.5 m = 262.5).
        assertEquals(
            listOf(262.5),
            e.update(EscortInput(1.0, at(100.0), 240.0, at(240.0), 1.0)).moves()
        )
        val door = e.update(EscortInput(2.0, at(260.0), 240.0, at(240.0), 2.0))
        assertEquals(EscortState.DOOR, e.state)
        assertEquals(listOf(EscortCue.OPEN_DOOR), door.says())
        assertTrue(e.update(EscortInput(3.0, at(260.0), 250.0, at(250.0), 3.0)).isEmpty())
        assertEquals(listOf(EscortCue.DOOR_THANKS), e.doorOpened().says())
        assertEquals(
            listOf(325.0),
            e.update(EscortInput(4.0, at(262.0), 250.0, at(250.0), 4.0)).moves()
        )
    }

    @Test
    fun `when temi gives up on an obstacle it says so and retries after a pause`() {
        val e = hand()
        e.update(EscortInput(0.0, at(0.0), 0.0, at(0.0), 0.0))
        val blocked = e.update(EscortInput(1.0, at(40.0), 20.0, at(20.0), 1.0, GoStatus.ABORT))
        assertEquals(listOf(EscortCue.BLOCKED), blocked.says())
        assertTrue(e.update(EscortInput(3.0, at(40.0), 20.0, at(20.0), 3.0)).isEmpty())
        assertEquals(
            listOf(95.0),
            e.update(EscortInput(7.0, at(40.0), 20.0, at(20.0), 7.0)).moves()
        )
    }

    @Test
    fun `in a pocket temi leads one leg, turns round and waits to see the walker`() {
        val e = EscortController(path, emptyList(), listOf(400.0), Carry.POCKET, m)
        assertEquals(listOf(200.0), e.update(EscortInput(0.0, at(0.0))).moves())
        val meet = e.update(EscortInput(10.0, at(195.0)))
        assertEquals(EscortState.MEETING, e.state)
        assertEquals(PI, meet.filterIsInstance<EscortAction.Face>().single().bearing, 1e-9)
        assertTrue(EscortAction.Detection(true) in meet)
        assertEquals(listOf(EscortCue.MEET), meet.says())
        // Someone 3 m away isn't close enough; 1.5 m is.
        assertTrue(e.update(EscortInput(11.0, at(195.0), detectedMeters = 3.0)).isEmpty())
        val go = e.update(EscortInput(12.0, at(195.0), detectedMeters = 1.5))
        assertEquals(listOf(EscortCue.CONTINUE), go.says())
        // Next leg ends at the turn (400), not 8 m on (395 would be first; the turn is the cap).
        assertEquals(listOf(395.0), go.moves())
    }

    @Test
    fun `temi arrives once the walker reaches the room too`() {
        val e = hand()
        e.update(EscortInput(0.0, at(0.0), 0.0, at(0.0), 0.0))
        e.update(EscortInput(1.0, at(690.0), 620.0, at(620.0), 1.0))
        assertEquals(EscortState.LEADING, e.state)
        val done = e.update(EscortInput(2.0, at(690.0), 650.0, at(650.0), 2.0))
        assertEquals(EscortState.ARRIVED, e.state)
        assertEquals(listOf(EscortCue.ARRIVED), done.says())
    }
}
