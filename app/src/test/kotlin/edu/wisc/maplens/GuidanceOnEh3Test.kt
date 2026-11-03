package edu.wisc.maplens

import edu.wisc.maplens.guidance.FakePoseStream
import edu.wisc.maplens.guidance.Maneuver
import edu.wisc.maplens.guidance.RouteGuide
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.bearingDifference
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.roomDestinations
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The demo flow on the traced EH3 graph: pick each room, walk there on the fake pose stream. */
class GuidanceOnEh3Test {
    @Test
    fun `every EH3 room can be picked, guided and reached on the fake pose stream`() {
        val file = listOf("../data/fixtures/maps", "data/fixtures/maps")
            .map { File(it, "eh-3-elevators-evac.graph.json") }
            .firstOrNull { it.exists() } ?: return
        val graph = FloorGraphJson.decode(file.readText())
        val k = Demo.STEP_LENGTH_PX
        for (room in graph.roomDestinations()) {
            val session = NavigationSession(AStarRouter())
            session.onMapCaptured(graph)
            session.onGraphVerified(graph)
            assertTrue(session.onCalibratedToRoom(k, room), room)
            val guide = RouteGuide(session.graph!!, session.route!!)
            assertEquals(Maneuver.ARRIVE, guide.cues.last().maneuver, room)

            val walk = FakePoseStream(guide.path, k)
            var last = guide.instruction(walk.current())
            while (!walk.finished) {
                val pose = walk.step()
                session.onPose(pose)
                // The fake walker faces along the route, so heading and route never disagree.
                val along = guide.path.bearingAt(guide.progressPx(pose))
                assertTrue(bearingDifference(pose.headingRad, along) < 1e-6, room)
                last = guide.instruction(pose)
            }
            assertEquals(Maneuver.ARRIVE, last.maneuver, room)
            assertEquals(0, last.stepsUntil, room)
        }
    }
}
