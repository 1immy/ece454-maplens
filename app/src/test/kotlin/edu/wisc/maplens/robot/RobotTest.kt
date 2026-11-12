package edu.wisc.maplens.robot

import edu.wisc.maplens.model.PixelPoint
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RobotProtocolTest {
    @Test
    fun `every command survives a round trip`() {
        val commands = listOf(
            RobotCommand.Hello("MapLens"),
            RobotCommand.GoTo(1.5, -2.25, null, 0.6),
            RobotCommand.GoTo(1.0, 2.0, 0.5, 0.4),
            RobotCommand.GoToLocation("room 3418", 0.6),
            RobotCommand.Stop,
            RobotCommand.TurnBy(-90),
            RobotCommand.Speak("Please open the door for me"),
            RobotCommand.Detection(true, 2.5),
            RobotCommand.Screen("Waiting for you", "door_open"),
            RobotCommand.ListLocations,
        )
        for (c in commands) assertEquals(c, RobotProtocol.decodeCommand(RobotProtocol.encode(c)))
    }

    @Test
    fun `every event survives a round trip`() {
        val events = listOf(
            RobotEvent.Hello("temi", "1.136.0", 80),
            RobotEvent.Pose(3.0, -1.0, 0.25),
            RobotEvent.GoToStatus(GoStatus.ABORT, "Obstacle"),
            RobotEvent.Detection(true, 1.2, -0.1),
            RobotEvent.Locations(listOf("home base", "3418")),
            RobotEvent.Button("door_open"),
            RobotEvent.Error("battery low"),
        )
        for (e in events) assertEquals(e, RobotProtocol.decodeEvent(RobotProtocol.encode(e)))
    }

    @Test
    fun `unknown or broken lines are ignored, unknown fields too`() {
        assertNull(RobotProtocol.decodeEvent("not json"))
        assertNull(RobotProtocol.decodeEvent("""{"type":"dance"}"""))
        assertEquals(
            RobotEvent.Pose(1.0, 2.0, 0.0),
            RobotProtocol.decodeEvent("""{"type":"pose","x":1,"y":2,"yaw":0,"extra":true}"""),
        )
    }
}

class MapAlignmentTest {
    @Test
    fun `two anchors recover rotation, scale, shift and the y flip`() {
        // Map = robot rotated, 25 px per metre, shifted; y flips between the frames.
        val truth = { x: Double, y: Double -> PixelPoint(100 + 25 * y, 400 + 25 * x) }
        val anchors = listOf(
            Anchor("J1", truth(0.0, 0.0), 0.0, 0.0),
            Anchor("J2", truth(10.0, 0.0), 10.0, 0.0),
            Anchor("J3", truth(0.0, 6.0), 0.0, 6.0),
        )
        val fit = assertNotNull(MapAlignment.fit(anchors))
        assertEquals(25.0, fit.scale, 1e-6)
        assertTrue(fit.rmsPx < 1e-6, "rms ${fit.rmsPx}")
        val p = fit.toMap(4.0, 3.0)
        assertEquals(truth(4.0, 3.0).x, p.x, 1e-6)
        assertEquals(truth(4.0, 3.0).y, p.y, 1e-6)
        val (x, y) = fit.toRobot(p)
        assertEquals(4.0, x, 1e-6)
        assertEquals(3.0, y, 1e-6)
        // Robot facing +x (yaw 0) faces map +y, i.e. bearing π/2 (down the image).
        assertEquals(PI / 2, fit.toMapBearing(0.0), 1e-6)
        assertEquals(0.0, fit.toRobotYaw(PI / 2), 1e-6)
    }

    @Test
    fun `one anchor is not enough`() {
        assertNull(MapAlignment.fit(listOf(Anchor("J1", PixelPoint(0.0, 0.0), 0.0, 0.0))))
    }
}
