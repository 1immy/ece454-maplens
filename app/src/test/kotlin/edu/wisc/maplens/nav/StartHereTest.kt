package edu.wisc.maplens.nav

import edu.wisc.maplens.live.facingOptions
import edu.wisc.maplens.mapProblems
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.nodesForLabel
import edu.wisc.maplens.routing.routeToLabel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "I'm here" at a room: START moves there, and everything still routes from it. */
class StartHereTest {
    private fun eh3(): FloorGraph? = listOf("../data/fixtures/maps", "data/fixtures/maps")
        .map { File(it, "eh-3-elevators-evac.graph.json") }
        .firstOrNull { it.exists() }
        ?.let { FloorGraphJson.decode(it.readText()) }

    @Test
    fun `starting at a room keeps the map valid and routes from there`() {
        val base = eh3() ?: return
        val door = base.nodesForLabel("3546").first()
        val moved = base.startingAt(door)
        assertEquals(door, moved.startNodeId)
        assertEquals(NodeType.START, moved.nodes.getValue(door).type)
        assertTrue(moved.nodes.values.count { it.type == NodeType.START } == 1)
        // The checks a saved map passes still pass: navigation accepts it.
        assertTrue(mapProblems(moved).none { it.blocksSave }, "${mapProblems(moved)}")
        // Its own room is still listed; other rooms route from the new start.
        assertTrue("3546" in moved.places().mapNotNull { it.label })
        val route = assertNotNull(AStarRouter().routeToLabel(moved, door, "3418"))
        assertEquals(door, route.nodeIds.first())
        // Unchanged for the poster's own START.
        assertEquals(base, base.startingAt(base.startNodeId))
    }

    @Test
    fun `facing choices at a room name the room, not the poster`() {
        val base = eh3() ?: return
        val door = base.nodesForLabel("3546").first()
        val at = base.nodes.getValue(door).position
        // The room's number printed beside the corridor: facing it is facing the room.
        val printed = PixelPoint(at.x, at.y - 60)
        val options = base.startingAt(door).facingOptions(door, printed, "room 3546")
        assertEquals(4, options.size)
        assertTrue(options.none { "poster" in it.description }, "$options")
        assertTrue(
            options.any { it.description == "facing room 3546" } ||
                options.any { it.description == "with your back to room 3546" }
        )
    }
}
