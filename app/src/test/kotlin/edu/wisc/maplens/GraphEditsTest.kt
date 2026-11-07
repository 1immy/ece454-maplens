package edu.wisc.maplens

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.routeToLabel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GraphEditsTest {
    /**
     *   W --- D(3650) --- J --- E
     *                     |
     *                     S
     */
    private val graph = graphOf(
        listOf(
            Node("W", PixelPoint(0.0, 0.0), NodeType.DEAD_END),
            Node("D", PixelPoint(100.0, 0.0), NodeType.DOOR),
            Node("J", PixelPoint(200.0, 0.0), NodeType.JUNCTION),
            Node("E", PixelPoint(400.0, 0.0), NodeType.DEAD_END),
            Node("S", PixelPoint(200.0, 150.0), NodeType.START),
        ),
        listOf("W" to "D", "D" to "J", "J" to "E", "S" to "J"),
    )

    private fun graphOf(nodes: List<Node>, links: List<Pair<String, String>>): FloorGraph {
        val byId = nodes.associateBy { it.id }
        return FloorGraph(
            "b",
            "3",
            byId,
            links.mapIndexed { i, (a, b) ->
                val points = listOf(byId.getValue(a).position, byId.getValue(b).position)
                Edge("e$i", a, b, points, FloorGraphJson.polylineLength(points))
            },
            listOf(Landmark("3650", "D", 0.9f)),
            "S",
        )
    }

    private fun FloorGraph.blocking() = mapProblems(this).filter { it.blocksSave }

    @Test
    fun `moving a point carries its corridors and their lengths`() {
        val moved = graph.moveNode("J", PixelPoint(200.0, 50.0))
        val edge = moved.edges.first { it.from == "J" && it.to == "E" }
        assertEquals(PixelPoint(200.0, 50.0), edge.polyline.first())
        assertEquals(PixelPoint(400.0, 0.0), edge.polyline.last())
        assertEquals(100.0, moved.edges.first { it.from == "S" }.lengthPx)
        assertTrue(moved.blocking().isEmpty())
    }

    @Test
    fun `a point added on a corridor splits it and keeps labels and total length`() {
        val (added, id) = graph.addPoint(PixelPoint(300.0, 3.0), snapPx = 10.0)
        assertEquals(NodeType.JUNCTION, added.nodes.getValue(id).type)
        assertEquals(PixelPoint(300.0, 0.0), added.nodes.getValue(id).position)
        assertEquals(graph.edges.size + 1, added.edges.size)
        assertEquals(graph.edges.sumOf { it.lengthPx }, added.edges.sumOf { it.lengthPx }, 1e-6)
        assertEquals(graph.landmarks, added.landmarks)
        assertTrue(added.blocking().isEmpty())
    }

    @Test
    fun `a point on top of a corridor end reuses it instead of making a zero edge`() {
        val (added, id) = graph.addPoint(PixelPoint(399.0, 0.0), snapPx = 10.0)
        assertEquals("E", id)
        assertEquals(graph, added)
    }

    @Test
    fun `a point off every corridor blocks saving until it is linked`() {
        val (added, id) = graph.addPoint(PixelPoint(400.0, 300.0), snapPx = 10.0)
        assertEquals(1, added.blocking().size)
        assertEquals(added.nodes.getValue(id).position, added.blocking().single().at)

        val linked = added.connect(id, "E")
        assertTrue(linked.blocking().isEmpty())
        assertEquals(NodeType.JUNCTION, linked.nodes.getValue("E").type)
    }

    @Test
    fun `linking refuses the same point and an existing corridor`() {
        assertFailsWith<IllegalArgumentException> { graph.connect("J", "J") }
        assertFailsWith<IllegalArgumentException> { graph.connect("E", "J") }
    }

    @Test
    fun `deleting a door mid-corridor rejoins the corridor and removes its label`() {
        val deleted = graph.deleteNode("D")
        assertFalse("D" in deleted.nodes)
        assertTrue(deleted.landmarks.isEmpty())
        assertTrue(deleted.blocking().isEmpty())
        val joined = deleted.edges.single { "W" in listOf(it.from, it.to) }
        assertEquals(200.0, joined.lengthPx)
        assertEquals(setOf("W", "J"), setOf(joined.from, joined.to))
        assertEquals(joined.polyline.first(), deleted.nodes.getValue(joined.from).position)
        assertEquals(joined.polyline.last(), deleted.nodes.getValue(joined.to).position)
    }

    @Test
    fun `deleting a junction disconnects the map and blocks saving`() {
        val deleted = graph.deleteNode("J")
        assertFalse(deleted.edges.any { "J" in listOf(it.from, it.to) })
        assertTrue(deleted.blocking().isNotEmpty())
    }

    @Test
    fun `deleting a corridor blocks saving and undoing it clears the problem`() {
        val history = EditHistory(graph)
        history.push(graph.deleteEdge("e3"))
        assertTrue(history.current.blocking().isNotEmpty())
        assertEquals(NodeType.START, history.current.nodes.getValue("S").type)
        history.undo()
        assertEquals(graph, history.current)
        assertFalse(history.canUndo)
    }

    @Test
    fun `deleting START leaves it unset rather than choosing another point`() {
        val deleted = graph.deleteNode("S")
        assertEquals("", deleted.startNodeId)
        assertTrue(deleted.blocking().any { it.message.startsWith("Set START") })
    }

    @Test
    fun `an unset START is never filled in and blocks the session`() {
        val draft = graph.copy(startNodeId = "")
        val session = NavigationSession(AStarRouter())
        session.onMapCaptured(draft)
        assertFailsWith<IllegalArgumentException> { session.onGraphVerified(draft) }
        assertEquals(Phase.VERIFY, session.phase)

        val (fixed, id) = draft.setStart(PixelPoint(300.0, 0.0))
        assertEquals(id, fixed.startNodeId)
        session.onGraphVerified(fixed)
        assertTrue(session.graph!!.verified)
    }

    @Test
    fun `moving START removes the old point and rejoins its corridor`() {
        val mid = graph.setStart(PixelPoint(200.0, 80.0))
        val (moved, id) = mid.first.setStart(PixelPoint(300.0, 2.0))
        assertEquals(NodeType.START, moved.nodes.getValue(id).type)
        assertEquals(1, moved.nodes.values.count { it.type == NodeType.START })
        assertFalse(mid.second in moved.nodes)
        assertEquals(id, moved.startNodeId)
    }

    @Test
    fun `START on a corridor end takes it over but never a door`() {
        val (onEnd, id) = graph.setStart(PixelPoint(400.0, 0.0))
        assertEquals("E", id)
        assertEquals(NodeType.START, onEnd.nodes.getValue("E").type)

        val failure = assertFailsWith<IllegalArgumentException> {
            graph.setStart(PixelPoint(100.0, 0.0))
        }
        assertTrue(failure.message!!.contains("door"))
    }

    @Test
    fun `a new door keeps other rooms and routes by its label`() {
        val (placed, door) = graph.placeDoor("36 60a", PixelPoint(300.0, 4.0), snapPx = 10.0)
        assertEquals(NodeType.DOOR, placed.nodes.getValue(door).type)
        assertEquals(setOf("3650", "3660A"), placed.landmarks.map { it.label }.toSet())
        assertEquals(door, AStarRouter().routeToLabel(placed, "S", "3660A")!!.nodeIds.last())
    }

    @Test
    fun `a new door does not take over another room's door or START`() {
        val (placed, door) = graph.placeDoor("3651", PixelPoint(105.0, 0.0), snapPx = 10.0)
        assertTrue(door != "D")
        assertEquals("D", placed.landmarks.first { it.label == "3650" }.nodeId)

        val (nearStart, other) = graph.placeDoor("3652", PixelPoint(200.0, 145.0), snapPx = 10.0)
        assertTrue(other != "S")
        assertEquals(NodeType.START, nearStart.nodes.getValue("S").type)
    }

    @Test
    fun `a new door needs a corridor under it`() {
        assertFailsWith<IllegalArgumentException> {
            graph.placeDoor("3660", PixelPoint(300.0, 300.0), snapPx = 10.0)
        }
    }

    @Test
    fun `a label can be replaced and an empty one is refused`() {
        val fixed = graph.setLabel("D", "3650", "3555a")
        assertEquals(listOf("3555A"), fixed.landmarks.map { it.label })
        assertEquals(1f, fixed.landmarks.single().ocrConfidence)
        assertFailsWith<IllegalArgumentException> { graph.setLabel("D", "3650", " ! ") }
        assertEquals(graph.landmarks, graph.setLabel("D", "3650", "3650").landmarks)
    }

    @Test
    fun `corridors that cross block saving and one tap adds the junction`() {
        val crossed = graphOf(
            listOf(
                Node("S", PixelPoint(0.0, 0.0), NodeType.START),
                Node("A", PixelPoint(100.0, 100.0), NodeType.DEAD_END),
                Node("B", PixelPoint(0.0, 100.0), NodeType.DEAD_END),
                Node("C", PixelPoint(100.0, 0.0), NodeType.DEAD_END),
                Node("D", PixelPoint(0.0, 200.0), NodeType.DOOR),
            ),
            listOf("S" to "A", "B" to "C", "S" to "B", "B" to "D"),
        )
        val problem = crossed.blocking().single()
        val crossing = checkNotNull(problem.crossing)
        assertEquals(PixelPoint(50.0, 50.0), crossing.point)

        val (fixed, junction) = crossed.joinAt(crossing)
        assertEquals(PixelPoint(50.0, 50.0), fixed.nodes.getValue(junction).position)
        assertEquals(4, fixed.edges.count { it.from == junction || it.to == junction })
        assertTrue(fixed.blocking().isEmpty())
    }

    @Test
    fun `corridors sharing an end do not count as crossing`() {
        assertTrue(graph.crossings().isEmpty())
    }

    @Test
    fun `one room may have several doors but a shared door is only a warning`() {
        val twoDoors = graph.placeDoor("3650", PixelPoint(300.0, 0.0), snapPx = 5.0).first
        assertTrue(mapProblems(twoDoors).isEmpty())

        val shared = graph.setLabel("D", null, "3651")
        val warning = mapProblems(shared).single()
        assertFalse(warning.blocksSave)
        assertTrue(warning.message.contains("share"))
    }

    @Test
    fun `a room off a door only warns`() {
        val onJunction = graph.setLabel("J", null, "3700")
        val problem = mapProblems(onJunction).single()
        assertFalse(problem.blocksSave)
        assertEquals(PixelPoint(200.0, 0.0), problem.at)
    }

    @Test
    fun `a tap picks the closest points first and the corridor only when no point is close`() {
        val close = graph.pickAt(PixelPoint(149.0, 0.0), slopPx = 120.0)
        assertEquals(listOf("D", "J"), assertIs<Pick.Points>(close).ids)

        // A finger right on one point means that point, even with a neighbour in reach.
        assertEquals(Pick.Points(listOf("D")), graph.pickAt(PixelPoint(101.0, 0.0), slopPx = 120.0))
        assertEquals(Pick.Corridor("e2"), graph.pickAt(PixelPoint(300.0, 2.0), slopPx = 5.0))
        assertEquals(Pick.Nothing, graph.pickAt(PixelPoint(300.0, 200.0), slopPx = 5.0))
    }

    @Test
    fun `every fixture graph on disk still passes the save rules`() {
        val folder = listOf("../data/fixtures/maps", "data/fixtures/maps")
            .map(::File)
            .firstOrNull { it.exists() } ?: return
        val graphs = folder.listFiles { file -> file.name.endsWith(".graph.json") }.orEmpty()
        assertTrue(graphs.isNotEmpty())
        for (file in graphs) {
            val problems = mapProblems(FloorGraphJson.decode(file.readText()))
            assertEquals(emptyList(), problems.filter { it.blocksSave }, file.name)
        }
    }
}

class MapDraftTest {
    private val graph = FloorGraph(
        "b",
        "3",
        listOf(
            Node("S", PixelPoint(0.0, 0.0), NodeType.START),
            Node("D", PixelPoint(100.0, 0.0), NodeType.DOOR),
        ).associateBy { it.id },
        listOf(
            Edge("e", "S", "D", listOf(PixelPoint(0.0, 0.0), PixelPoint(100.0, 0.0)), 100.0),
        ),
        listOf(Landmark("3555A", "D", 0.8f)),
        "S",
    )
    private val misread = RoomDraft("3555A", 0, 0, 10, 10, "D", 0.8f)
    private val possible = RoomDraft("3602A", 0, 0, 10, 10, null, 0.7f, possible = true)
    private val draft = MapDraft(graph, listOf(misread, possible))

    @Test
    fun `correcting a doubtful number updates the room and its label`() {
        val fixed = draft.renameRoom(0, "3556a")
        assertEquals("3556A", fixed.rooms[0].label)
        assertEquals(1f, fixed.rooms[0].confidence)
        assertEquals(listOf("3556A"), fixed.graph.landmarks.map { it.label })
        assertEquals(RoomCheck.CLEAR, roomCheck(fixed.rooms[0].confidence, false, true))
        assertFailsWith<IllegalArgumentException> { draft.renameRoom(0, "  ") }
    }

    @Test
    fun `accepting a reading clears its doubt and a possible room still needs a door`() {
        val accepted = draft.acceptRoom(0)
        assertEquals(1f, accepted.rooms[0].confidence)
        assertEquals(1f, accepted.graph.landmarks.single().ocrConfidence)

        val found = draft.acceptRoom(1).rooms[1]
        assertFalse(found.possible)
        assertEquals(null, found.entranceNodeId)
        assertEquals(1, draft.acceptRoom(1).graph.landmarks.size)
    }

    @Test
    fun `placing a door puts a possible room in the graph`() {
        val placed = draft.placeRoomDoor(1, PixelPoint(50.0, 2.0), snapPx = 10.0)
        val door = checkNotNull(placed.rooms[1].entranceNodeId)
        assertEquals(door, placed.graph.landmarks.first { it.label == "3602A" }.nodeId)
        assertFalse(placed.rooms[1].possible)
    }

    @Test
    fun `moving a room's door drops the label from the old one`() {
        val moved = draft.placeRoomDoor(0, PixelPoint(50.0, 0.0), snapPx = 10.0)
        assertEquals(1, moved.graph.landmarks.size)
        assertEquals(moved.rooms[0].entranceNodeId, moved.graph.landmarks.single().nodeId)
    }

    @Test
    fun `removing a false reading removes its label`() {
        val removed = draft.removeRoom(0)
        assertEquals(listOf(possible), removed.rooms)
        assertTrue(removed.graph.landmarks.isEmpty())
    }

    @Test
    fun `deleting a door sends its room back to needing one`() {
        val deleted = draft.withGraph(graph.deleteNode("D"))
        assertEquals(null, deleted.rooms[0].entranceNodeId)
        assertEquals(RoomCheck.ENTRANCE, roomCheck(1f, false, false))
    }

    @Test
    fun `a label typed on a point follows into the room list`() {
        val fixed = draft.labelPoint("D", "3555A", "3556A")
        assertEquals("3556A", fixed.rooms[0].label)
        assertEquals(listOf("3556A"), fixed.graph.landmarks.map { it.label })
    }

    @Test
    fun `accepting a shared door clears the flag so checking can finish`() {
        val shared = MapDraft(
            graph.setLabel("D", null, "3556"),
            listOf(misread, RoomDraft("3556", 0, 0, 10, 10, "D", 0.99f)),
        )
        assertEquals(RoomCheck.ENTRANCE, shared.checkOf(1))

        val accepted = shared.acceptRoom(1)
        assertEquals(RoomCheck.CLEAR, accepted.checkOf(1))
        assertEquals(listOf(0), accepted.roomsToCheck())
    }

    @Test
    fun `an accepted room is flagged again if its door is deleted`() {
        val accepted = draft.acceptRoom(0)
        assertEquals(RoomCheck.CLEAR, accepted.checkOf(0))
        val noDoor = accepted.withGraph(accepted.graph.deleteNode("D"))
        assertEquals(RoomCheck.ENTRANCE, noDoor.checkOf(0))
    }

    @Test
    fun `flagged rooms come in checking order and skipping wraps round`() {
        // Possible rooms come first, then doubtful numbers.
        assertEquals(listOf(1, 0), draft.roomsToCheck())
        assertEquals(1, draft.nextToCheck())
        assertEquals(0, draft.nextToCheck(draft.checkOrder(1)))
        assertEquals(1, draft.nextToCheck(draft.checkOrder(0)))
    }

    @Test
    fun `fixing a room leaves the rest of the order in place`() {
        val order = draft.checkOrder(1)
        val fixed = draft.placeRoomDoor(1, PixelPoint(50.0, 0.0), snapPx = 10.0)
        assertEquals(0, fixed.nextToCheck(order))
        assertEquals(null, fixed.acceptRoom(0).nextToCheck(order))
    }

    @Test
    fun `a door finds what the model read for it`() {
        assertEquals(listOf(0), draft.roomsAt("D"))
        assertEquals(emptyList(), draft.roomsAt("S"))
        val shared = draft.copy(rooms = draft.rooms + misread.copy(label = "3556"))
        assertEquals(listOf(0, 2), shared.roomsAt("D"))
    }

    @Test
    fun `a graph without a parse gets its rooms from its labels`() {
        val rooms = graph.roomsFromLabels()
        assertEquals(listOf("3555A"), rooms.map { it.label })
        assertEquals("D", rooms.single().entranceNodeId)
    }
}
