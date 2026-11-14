package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.SignObservation
import edu.wisc.maplens.model.StepSource
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignReanchorTest {
    private val right = 0.0
    private val down = PI / 2

    private fun graph(
        points: Map<String, PixelPoint>,
        doors: Map<String, String>,
        vararg links: Pair<String, String>,
    ): FloorGraph {
        val nodes = points.entries.mapIndexed { i, (id, p) ->
            val type = when {
                i == 0 -> NodeType.START
                id in doors -> NodeType.DOOR
                else -> NodeType.JUNCTION
            }
            id to Node(id, p, type)
        }.toMap()
        val edges = links.map { (a, b) ->
            Edge("$a-$b", a, b, emptyList(), points.getValue(a).distanceTo(points.getValue(b)))
        }
        val landmarks = doors.map { (node, label) -> Landmark(label, node, 1f) }
        return FloorGraph("t", "1", nodes, edges, landmarks, points.keys.first())
    }

    /** One corridor, S at 0, room 3410's door at 200, room 3450's at 500, end at 600 px. */
    private val corridor = graph(
        linkedMapOf(
            "S" to PixelPoint(0.0, 0.0),
            "D1" to PixelPoint(200.0, 0.0),
            "D2" to PixelPoint(500.0, 0.0),
            "E" to PixelPoint(600.0, 0.0),
        ),
        mapOf("D1" to "3410", "D2" to "3450"),
        "S" to "D1",
        "D1" to "D2",
        "D2" to "E",
    )

    private val config = TrackingConfig(autoCalibrateStepLength = false)

    private fun walked(steps: Int, k: Double = 10.0, seed: Int = 1): ParticleTracker {
        val tracker = ParticleTracker(config, Random(seed))
        tracker.start(corridor, "S", right, k)
        repeat(steps) {
            tracker.onMotion(MotionEvent.Step(it + 1L, right, StepSource.STEP_DETECTOR))
        }
        return tracker
    }

    private fun sign(text: String) = SignObservation(0L, text, 0.9f, null)

    @Test
    fun `a nearby sign re-anchors the cloud on its door`() {
        // The tracker thinks 150 px (k 10 x 15 steps); the walker is really at room 3410.
        val tracker = walked(15)
        val before = tracker.pose
        val result = tracker.onSighting(SignSighting(sign("3410")))
        assertTrue(result.accepted, "$result")
        assertEquals("D1", result.doorNodeId)
        assertEquals(200.0, tracker.pose.position.x, 25.0)
        assertTrue(before.position.x < 170.0)
    }

    @Test
    fun `bearing and distance put the walker back from the door along the line of sight`() {
        val tracker = walked(15)
        // Looking right at 3410, about 3 m (43 px at k 10) away.
        val result = tracker.onSighting(SignSighting(sign("3410"), right, 3.0 / 0.7 * 10.0))
        assertTrue(result.accepted)
        assertEquals(157.0, tracker.pose.position.x, 15.0)
    }

    @Test
    fun `with the A5 gate a sign far from the cloud is rejected and changes nothing`() {
        val tracker = ParticleTracker(config.copy(signRelocates = false), Random(1))
        tracker.start(corridor, "S", right, 10.0)
        repeat(15) {
            tracker.onMotion(MotionEvent.Step(it + 1L, right, StepSource.STEP_DETECTOR))
        }
        val before = tracker.pose
        val result = tracker.onSighting(SignSighting(sign("3450")))
        assertFalse(result.accepted)
        assertEquals(SignOutcome.TOO_FAR, result.reason)
        assertEquals(before, tracker.pose)
    }

    @Test
    fun `a sign far from a drifted cloud moves it to the door and says so`() {
        // Field test: the dot had drifted and a correct sign at the walker's door was refused.
        val tracker = walked(15)
        val result = tracker.onSighting(SignSighting(sign("3450")))
        assertTrue(result.accepted, "$result")
        assertTrue(result.relocated)
        assertEquals("D2", result.doorNodeId)
        assertEquals(500.0, tracker.pose.position.x, 25.0)
        // Walking on from the door continues from there.
        val next = tracker.onMotion(MotionEvent.Step(99L, right, StepSource.STEP_DETECTOR))
        assertEquals(510.0, next.position.x, 25.0)
    }

    @Test
    fun `a sign straight ahead of the walker fixes the heading offset`() {
        // Room 3410 is below the corridor; its sign hangs on the lower wall at the door. The
        // walker stands at the door and points the phone at it: truly "down" (π/2). The
        // drifted heading says 0.5 rad less, so θ0 must turn by +0.5.
        val tracker = walked(20)
        val wall = 1.0 / 0.7 * 10.0
        val result = tracker.onSighting(
            SignSighting(sign("3410"), down - 0.5, wall, roomCenter = PixelPoint(200.0, 60.0)),
        )
        assertTrue(result.accepted)
        assertEquals(0.5, assertNotNull(result.headingFixRad), 1e-9)
        assertEquals(200.0, tracker.pose.position.x, 15.0)
    }

    @Test
    fun `a sign seen at an angle places the walker along the corridor and fixes heading`() {
        // 3 m from the sign on the lower wall, 1 m off the centreline: the walker is √8 m
        // (about 40 px) before the door and truly looks atan2(1, √8) ≈ 19.5° right of ahead.
        val tracker = walked(15)
        val px = 10.0 / 0.7
        val truth = kotlin.math.atan2(1.0, kotlin.math.sqrt(8.0))
        val result = tracker.onSighting(
            SignSighting(sign("3410"), truth + 0.3, 3 * px, roomCenter = PixelPoint(200.0, 60.0)),
        )
        assertEquals(-0.3, assertNotNull(result.headingFixRad), 0.01)
        assertEquals(200.0 - kotlin.math.sqrt(8.0) * px, tracker.pose.position.x, 15.0)
    }

    @Test
    fun `an implausible heading fix is not applied`() {
        val tracker = walked(20)
        // Measured pointing up while the sign is on the lower wall: 180° off, a wrong match.
        val result = tracker.onSighting(
            SignSighting(
                sign("3410"),
                -down,
                1.0 / 0.7 * 10.0,
                roomCenter = PixelPoint(200.0, 60.0)
            ),
        )
        assertTrue(result.accepted)
        assertNull(result.headingFixRad)
    }

    @Test
    fun `an unknown or ambiguous number is not matched`() {
        val tracker = walked(5)
        assertEquals(
            SignOutcome.UNKNOWN_ROOM,
            tracker.onSighting(SignSighting(sign("9999"))).reason
        )
        // 3430 is one edit from both 3410 and 3450: never guess.
        assertEquals(
            SignOutcome.UNKNOWN_ROOM,
            tracker.onSighting(SignSighting(sign("3430"))).reason
        )
        assertFalse(tracker.onSign(sign("hello")))
    }

    @Test
    fun `a common OCR misread still matches its room`() {
        val tracker = walked(15)
        assertTrue(tracker.onSign(sign("34l0")))
        assertTrue(walked(15).onSign(sign("3 4 1 O")))
    }

    @Test
    fun `turns at junctions refine the step length toward the walker's real stride`() {
        // A staircase of 200 px legs: right, down, right, down, right. The walker's real step
        // is 12.5 px (16 steps a leg) but the tracker starts at 10.
        val stairs = graph(
            linkedMapOf(
                "S" to PixelPoint(0.0, 0.0),
                "A" to PixelPoint(200.0, 0.0),
                "B" to PixelPoint(200.0, 200.0),
                "C" to PixelPoint(400.0, 200.0),
                "D" to PixelPoint(400.0, 400.0),
                "E" to PixelPoint(600.0, 400.0),
            ),
            emptyMap(),
            "S" to "A",
            "A" to "B",
            "B" to "C",
            "C" to "D",
            "D" to "E",
        )
        val headings = listOf(right, down, right, down, right).flatMap { h -> List(16) { h } }
        val ks = mutableListOf<Double?>()
        for (on in listOf(true, false)) {
            val tracker = ParticleTracker(config.copy(landmarkStepLength = on), Random(4))
            tracker.start(stairs, "S", right, 10.0)
            headings.forEachIndexed { i, h ->
                tracker.onMotion(MotionEvent.Step(i + 1L, h, StepSource.STEP_DETECTOR))
            }
            ks += tracker.calibratedStepLengthPx
        }
        val refined = assertNotNull(ks[0])
        assertTrue(refined > 10.8, "k after three turn-to-turn legs: $refined")
        assertNull(ks[1], "switched off, k must stay as started")
    }
}
