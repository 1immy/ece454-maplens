package edu.wisc.maplens

import edu.wisc.maplens.model.ConfidenceCheck
import edu.wisc.maplens.model.ConfidenceLevel
import edu.wisc.maplens.model.ConfidenceState
import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.RecoveryAction
import edu.wisc.maplens.model.Route
import edu.wisc.maplens.routing.AStarRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationSessionTest {
    private val graph = FloorGraph(
        buildingId = "engineering-hall",
        floor = "3",
        nodes = listOf(
            Node("S", PixelPoint(0.0, 0.0), NodeType.START),
            Node("D", PixelPoint(100.0, 0.0), NodeType.DOOR),
        ).associateBy { it.id },
        edges = listOf(
            Edge("e1", "S", "D", listOf(PixelPoint(0.0, 0.0), PixelPoint(100.0, 0.0)), 100.0),
        ),
        landmarks = emptyList(),
        startNodeId = "S",
    )

    @Test
    fun `happy path walks every phase`() {
        val session = NavigationSession(AStarRouter())
        session.onMapCaptured(graph)
        session.onGraphVerified(graph)
        assertTrue(session.graph!!.verified)
        assertTrue(session.onCalibrated(k = 25.0, destinationNodeId = "D"))
        assertEquals(Phase.NAVIGATE, session.phase)

        session.onConfidence(low())
        assertEquals(Phase.RECOVER, session.phase)
        session.onConfidence(low().copy(level = ConfidenceLevel.HIGH, failedChecks = emptySet()))
        assertEquals(Phase.NAVIGATE, session.phase)

        session.onArrived()
        assertEquals(Phase.ARRIVED, session.phase)
    }

    @Test
    fun `a reroute replaces the route only while navigating`() {
        val session = NavigationSession(AStarRouter())
        val detour = Route(listOf("D", "S"), listOf("e1"), 100.0)
        assertFailsWith<IllegalStateException> { session.onRerouted(detour) }
        session.onMapCaptured(graph)
        session.onGraphVerified(graph)
        assertTrue(session.onCalibrated(25.0, "D"))
        session.onRerouted(detour)
        assertEquals(detour, session.route)
        assertEquals(Phase.NAVIGATE, session.phase)
        session.onArrived()
        assertFailsWith<IllegalStateException> { session.onRerouted(detour) }
    }

    @Test
    fun `calibrating to a room label routes to its door`() {
        val labelled = graph.copy(landmarks = listOf(Landmark("3546", "D", 1f)))
        val session = NavigationSession(AStarRouter())
        session.onMapCaptured(labelled)
        session.onGraphVerified(labelled)
        assertFalse(session.onCalibratedToRoom(k = 25.0, roomLabel = "9999"))
        assertEquals(Phase.CALIBRATE, session.phase)
        assertTrue(session.onCalibratedToRoom(k = 25.0, roomLabel = "3546"))
        assertEquals(listOf("S", "D"), session.route!!.nodeIds)
        assertEquals(25.0, session.stepLengthPx)
        assertEquals(Phase.NAVIGATE, session.phase)
    }

    @Test
    fun `captured map cannot be verified with a placeholder START`() {
        val draft = graph.copy(startNodeId = "D")
        val session = NavigationSession(AStarRouter())
        session.onMapCaptured(draft)
        assertFailsWith<IllegalArgumentException> { session.onGraphVerified(draft) }
        assertEquals(Phase.VERIFY, session.phase)
        assertFalse(session.graph!!.verified)
    }

    @Test
    fun `review identifies unreachable destinations by room number`() {
        val draft = graph.copy(
            edges = emptyList(),
            landmarks = listOf(Landmark("3546", "D", 1f)),
        )
        assertTrue(reviewProblems(draft).any { it == "Rooms unreachable from START: 3546." })
    }

    @Test
    fun `weak and possible reads are marked for checking`() {
        assertEquals(RoomCheck.CLEAR, roomCheck(0.99f, possible = false, hasOwnEntrance = true))
        assertEquals(RoomCheck.NOT_SURE, roomCheck(0.95f, possible = false, hasOwnEntrance = true))
        assertEquals(RoomCheck.POSSIBLE, roomCheck(0.99f, possible = true, hasOwnEntrance = false))
        assertEquals(RoomCheck.ENTRANCE, roomCheck(0.99f, possible = false, hasOwnEntrance = false))
    }

    private fun low() = ConfidenceState(
        timestampNanos = 0L,
        level = ConfidenceLevel.LOW,
        failedChecks = setOf(ConfidenceCheck.SPREAD),
        suggestedRecovery = RecoveryAction.SCAN_SIGN,
    )
}
