package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.StepSource
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepLengthCalibratorTest {
    private val right = 0.0
    private val down = PI / 2
    private val left = PI

    /** Straight edges between named points; the first node is START. */
    private fun graph(
        points: Map<String, PixelPoint>,
        vararg links: Pair<String, String>
    ): FloorGraph {
        val nodes = points.entries.mapIndexed { i, (id, p) ->
            id to Node(id, p, if (i == 0) NodeType.START else NodeType.JUNCTION)
        }.toMap()
        val edges = links.map { (a, b) ->
            Edge("$a-$b", a, b, emptyList(), points.getValue(a).distanceTo(points.getValue(b)))
        }
        return FloorGraph("test", "1", nodes, edges, emptyList(), points.keys.first())
    }

    /** 300 px right from S, through two doors, then a corner at A down to B. */
    private val ell = graph(
        mapOf(
            "S" to PixelPoint(0.0, 0.0),
            "D1" to PixelPoint(100.0, 0.0),
            "D2" to PixelPoint(200.0, 0.0),
            "A" to PixelPoint(300.0, 0.0),
            "B" to PixelPoint(300.0, 300.0),
        ),
        "S" to "D1",
        "D1" to "D2",
        "D2" to "A",
        "A" to "B",
    )

    private fun feed(calibrator: StepLengthCalibrator, headings: List<Double>) =
        headings.mapNotNull { calibrator.onStep(it) }

    @Test
    fun `k is corridor px over steps at the first turn`() {
        val calibrator = StepLengthCalibrator(ell, "S", right, initialStepLengthPx = 10.0)
        // A walker at 12 px/step: 25 steps to the corner, then down.
        val result = feed(calibrator, List(25) { right } + List(5) { down })
        assertEquals(1, result.size)
        assertEquals(12.0, result.single().stepLengthPx, 1e-9)
        assertEquals("A-B", result.single().turn.edgeId)
        assertEquals(2, result.single().stepsPast)
        assertTrue(calibrator.finished)
    }

    @Test
    fun `a short first leg is walked through and calibration uses the next turn`() {
        // 40 px right, then 300 px down, then left: the first turn is only 4 steps out.
        val zed = graph(
            mapOf(
                "S" to PixelPoint(0.0, 0.0),
                "J" to PixelPoint(40.0, 0.0),
                "K" to PixelPoint(40.0, 300.0),
                "L" to PixelPoint(-200.0, 300.0),
            ),
            "S" to "J",
            "J" to "K",
            "K" to "L",
        )
        val calibrator = StepLengthCalibrator(zed, "S", right, initialStepLengthPx = 15.0)
        val result = feed(calibrator, List(3) { right } + List(17) { down } + List(4) { left })
        assertEquals(340.0 / 20, result.single().stepLengthPx, 1e-9)
        assertEquals("K-L", result.single().turn.edgeId)
    }

    @Test
    fun `a single off-corridor step is not a turn`() {
        val calibrator = StepLengthCalibrator(ell, "S", right, initialStepLengthPx = 10.0)
        val wobble = List(10) { right } + down + List(14) { right } + List(3) { down }
        assertEquals(12.0, feed(calibrator, wobble).single().stepLengthPx, 1e-9)
    }

    @Test
    fun `two turn-offs that both fit the step count give up instead of guessing`() {
        // Down corridors at x = 150 and x = 170; 16 steps at k = 10 fit either.
        val comb = graph(
            mapOf(
                "S" to PixelPoint(0.0, 0.0),
                "X" to PixelPoint(150.0, 0.0),
                "Y" to PixelPoint(170.0, 0.0),
                "E" to PixelPoint(400.0, 0.0),
                "XD" to PixelPoint(150.0, 200.0),
                "YD" to PixelPoint(170.0, 200.0),
            ),
            "S" to "X",
            "X" to "Y",
            "Y" to "E",
            "X" to "XD",
            "Y" to "YD",
        )
        val calibrator = StepLengthCalibrator(comb, "S", right, initialStepLengthPx = 10.0)
        assertTrue(feed(calibrator, List(16) { right } + List(5) { down }).isEmpty())
        assertTrue(calibrator.finished)
    }

    @Test
    fun `a k far from the starting k is rejected`() {
        val calibrator = StepLengthCalibrator(ell, "S", right, initialStepLengthPx = 30.0)
        // 300 px in 25 steps is 12 px/step, 60% below the starting 30.
        assertTrue(feed(calibrator, List(25) { right } + List(3) { down }).isEmpty())
        assertTrue(calibrator.finished)
    }

    @Test
    fun `the tracker re-draws k and re-places the cloud after the turn`() {
        val tracker = ParticleTracker(random = Random(3))
        // Starting k 16 for a 12 px/step walker: without calibration the cloud overshoots.
        tracker.start(ell, "S", right, stepLengthPx = 16.0)
        var pose = tracker.pose
        (List(25) { right } + List(10) { down }).forEachIndexed { i, h ->
            pose = tracker.onMotion(MotionEvent.Step(i + 1L, h, StepSource.STEP_DETECTOR))
        }
        assertEquals(12.0, assertNotNull(tracker.calibratedStepLengthPx), 1e-9)
        // Confirmed on the second step down: step 27.
        assertEquals(27L, tracker.calibratedAtNanos)
        assertEquals(12.0, pose.stepLengthPx, 1.0)
        assertEquals("A-B", pose.edgeId)
        // 10 steps down at 12 px/step.
        assertEquals(120.0, pose.position.y, 25.0)
    }

    @Test
    fun `calibration can be turned off`() {
        val tracker = ParticleTracker(TrackingConfig(autoCalibrateStepLength = false), Random(3))
        tracker.start(ell, "S", right, stepLengthPx = 12.0)
        (List(25) { right } + List(5) { down }).forEachIndexed { i, h ->
            tracker.onMotion(MotionEvent.Step(i + 1L, h, StepSource.STEP_DETECTOR))
        }
        assertNull(tracker.calibratedStepLengthPx)
        assertNull(tracker.calibratedAtNanos)
    }
}
