package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.ConfidenceCheck
import edu.wisc.maplens.model.ConfidenceLevel
import edu.wisc.maplens.model.ConfidenceState
import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.RecoveryAction
import edu.wisc.maplens.model.StepSource
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FourCheckConfidenceMonitorTest {
    private val right = 0.0
    private val down = PI / 2

    /** k = 10 px/step at the 0.7 m stride: 14.3 px per metre, so 3 m is 42.9 px. */
    private val k = 10.0
    private val pxPerMeter = k / 0.7

    /**
     * S → J to the right; J branches up to U, down to D, and right through door R (room 3418)
     * to E. Every corridor is straight and axis-aligned.
     */
    private fun tee(withSign: Boolean = true): FloorGraph {
        val points = linkedMapOf(
            "S" to PixelPoint(0.0, 0.0),
            "J" to PixelPoint(100.0, 0.0),
            "U" to PixelPoint(100.0, -200.0),
            "D" to PixelPoint(100.0, 200.0),
            "R" to PixelPoint(250.0, 0.0),
            "E" to PixelPoint(400.0, 0.0),
        )
        val types = mapOf("S" to NodeType.START, "R" to NodeType.DOOR, "E" to NodeType.DEAD_END)
        val nodes = points.mapValues { (id, p) -> Node(id, p, types[id] ?: NodeType.JUNCTION) }
        val links = listOf("S" to "J", "J" to "U", "J" to "D", "J" to "R", "R" to "E")
        val edges = links.map { (a, b) ->
            Edge("$a-$b", a, b, emptyList(), points.getValue(a).distanceTo(points.getValue(b)))
        }
        val landmarks = if (withSign) listOf(Landmark("3418", "R", 1f)) else emptyList()
        return FloorGraph("test", "1", nodes, edges, landmarks, "S", verified = true)
    }

    private var clock = 0L

    private fun pose(
        x: Double,
        y: Double,
        heading: Double,
        spreadPx: Double = 5.0,
        top: Double = 0.95,
    ) = PoseEstimate(++clock, PixelPoint(x, y), heading, null, spreadPx, top, k)

    private fun FourCheckConfidenceMonitor.run(
        graph: FloorGraph,
        poses: List<PoseEstimate>,
    ): List<ConfidenceState> = poses.map { evaluate(graph, it) }

    /** Walking right along S → J → R with a tight cloud. */
    private fun straight(steps: Int, fromX: Double = 0.0) =
        List(steps) { pose(fromX + (it + 1) * k, 0.0, right) }

    @Test
    fun `straight walking with a tight cloud stays HIGH`() {
        val states = FourCheckConfidenceMonitor().run(tee(), straight(35))
        assertTrue(states.all { it.level == ConfidenceLevel.HIGH }, "$states")
        assertTrue(states.all { it.failedChecks.isEmpty() && it.suggestedRecovery == null })
    }

    @Test
    fun `spread over 3 m fails SPREAD only`() {
        val monitor = FourCheckConfidenceMonitor()
        val ok = monitor.evaluate(tee(), pose(50.0, 0.0, right, spreadPx = 2.9 * pxPerMeter))
        assertEquals(ConfidenceLevel.HIGH, ok.level)
        val wide = monitor.evaluate(tee(), pose(60.0, 0.0, right, spreadPx = 3.1 * pxPerMeter))
        assertEquals(ConfidenceLevel.LOW, wide.level)
        assertEquals(setOf(ConfidenceCheck.SPREAD), wide.failedChecks)
    }

    @Test
    fun `spread is judged in metres, so a longer k allows more pixels`() {
        val long = PoseEstimate(1, PixelPoint(50.0, 0.0), right, null, 60.0, 0.95, 20.0)
        // 60 px at k 20 is 2.1 m; at k 10 it would be 4.2 m.
        assertEquals(ConfidenceLevel.HIGH, FourCheckConfidenceMonitor().evaluate(tee(), long).level)
    }

    @Test
    fun `heaviest group under 0_6 of the weight fails GROUP_SPLIT`() {
        val monitor = FourCheckConfidenceMonitor()
        assertEquals(
            ConfidenceLevel.HIGH,
            monitor.evaluate(tee(), pose(50.0, 0.0, right, top = 0.6)).level,
        )
        val split = monitor.evaluate(tee(), pose(60.0, 0.0, right, top = 0.55))
        assertEquals(setOf(ConfidenceCheck.GROUP_SPLIT), split.failedChecks)
    }

    @Test
    fun `heading off the corridor fails HEADING_FIT only after a full window of steps`() {
        val monitor = FourCheckConfidenceMonitor()
        // 40° off the corridor at every step: too little to count as a turn, too much to fit.
        val skew = Math.toRadians(40.0)
        val states = monitor.run(tee(), List(12) { pose(110.0 + it * k, 0.0, skew) })
        assertTrue(states.take(9).none { ConfidenceCheck.HEADING_FIT in it.failedChecks })
        assertTrue(states.drop(9).all { ConfidenceCheck.HEADING_FIT in it.failedChecks })
    }

    @Test
    fun `brief wobbles under the window mean don't fail HEADING_FIT`() {
        val wobble = Math.toRadians(60.0)
        val poses = List(30) { pose(110.0 + it * k, 0.0, if (it % 5 == 0) wobble else right) }
        val states = FourCheckConfidenceMonitor().run(tee(), poses)
        assertTrue(states.none { ConfidenceCheck.HEADING_FIT in it.failedChecks }, "$states")
    }

    @Test
    fun `a turn where no corridor branches fails TURN_FIT until the heading fits again`() {
        val monitor = FourCheckConfidenceMonitor()
        // On J → R at x = 200: J is 10 steps back, R's corridors run left and right only.
        val before = monitor.run(tee(), List(3) { pose(170.0 + it * k, 0.0, right) })
        assertTrue(before.all { it.level == ConfidenceLevel.HIGH })
        val first = monitor.evaluate(tee(), pose(200.0, 0.0, down))
        assertTrue(ConfidenceCheck.TURN_FIT !in first.failedChecks, "one step is not a turn yet")
        val turn = monitor.evaluate(tee(), pose(200.0, 0.0, down))
        assertTrue(ConfidenceCheck.TURN_FIT in turn.failedChecks, "$turn")
        val still = monitor.evaluate(tee(), pose(200.0, 0.0, down))
        assertTrue(ConfidenceCheck.TURN_FIT in still.failedChecks)
        val back = monitor.evaluate(tee(), pose(210.0, 0.0, right))
        assertTrue(ConfidenceCheck.TURN_FIT !in back.failedChecks)
    }

    @Test
    fun `a turn at a junction passes TURN_FIT even before the estimate rounds the corner`() {
        val monitor = FourCheckConfidenceMonitor()
        monitor.run(tee(), straight(8))
        // Estimate still on S → J, 1 step short of J, heading already down the J → D branch.
        monitor.evaluate(tee(), pose(90.0, 0.0, down))
        val lagging = monitor.evaluate(tee(), pose(90.0, 0.0, down))
        assertTrue(ConfidenceCheck.TURN_FIT !in lagging.failedChecks, "$lagging")
        val rounded = monitor.run(tee(), List(10) { pose(100.0, 10.0 + it * k, down) })
        assertTrue(rounded.all { it.level == ConfidenceLevel.HIGH }, "$rounded")
    }

    @Test
    fun `LOW near a door suggests a scan, LOW with no door near suggests the next turn`() {
        val wide = 3.5 * pxPerMeter
        val nearDoor = FourCheckConfidenceMonitor().evaluate(tee(), pose(240.0, 0.0, right, wide))
        assertEquals(RecoveryAction.SCAN_SIGN, nearDoor.suggestedRecovery)
        val noSign = FourCheckConfidenceMonitor()
            .evaluate(tee(withSign = false), pose(240.0, 0.0, right, wide))
        assertEquals(RecoveryAction.NEXT_TURN_OR_DEAD_END, noSign.suggestedRecovery)
        // S is 17 m from the door: far outside max(3σ, 5 m) = 10.5 m.
        val far = FourCheckConfidenceMonitor().evaluate(tee(), pose(10.0, 0.0, right, wide))
        assertEquals(RecoveryAction.NEXT_TURN_OR_DEAD_END, far.suggestedRecovery)
    }

    @Test
    fun `two LOW spells under 20 steps apart get one scan prompt`() {
        val monitor = FourCheckConfidenceMonitor()
        val graph = tee()
        fun at(i: Int, top: Double) = pose(200.0 + (i % 5) * k, 0.0, right, top = top)
        val first = monitor.run(graph, List(3) { at(it, 0.5) })
        assertTrue(first.all { it.suggestedRecovery == RecoveryAction.SCAN_SIGN }, "$first")
        val calm = monitor.run(graph, List(5) { at(it, 0.9) })
        assertTrue(calm.all { it.suggestedRecovery == null })
        // Step 9 is 8 steps after the prompt: inside the 20-step cooldown.
        val second = monitor.run(graph, List(3) { at(it, 0.5) })
        assertTrue(second.all { it.suggestedRecovery == RecoveryAction.NEXT_TURN_OR_DEAD_END })
        monitor.run(graph, List(15) { at(it, 0.9) })
        // Step 27: 26 steps after the first prompt, so a new one is allowed.
        val third = monitor.evaluate(graph, at(0, 0.5))
        assertEquals(RecoveryAction.SCAN_SIGN, third.suggestedRecovery)
    }

    @Test
    fun `reset forgets a failed turn`() {
        val monitor = FourCheckConfidenceMonitor()
        monitor.run(tee(), List(3) { pose(170.0 + it * k, 0.0, right) })
        monitor.run(tee(), List(2) { pose(200.0, 0.0, down) })
        monitor.reset()
        val after = monitor.evaluate(tee(), pose(200.0, 0.0, down))
        assertTrue(after.failedChecks.isEmpty(), "$after")
        assertNull(after.suggestedRecovery)
    }

    @Test
    fun `with the particle tracker, a turn the map can't explain goes LOW`() {
        val graph = tee()
        val tracker = ParticleTracker(
            TrackingConfig(autoCalibrateStepLength = false, initialStepLengthSpread = 0.05),
            random = Random(3),
        )
        tracker.start(graph, "S", right, stepLengthPx = k)
        val monitor = FourCheckConfidenceMonitor()
        val headings = List(20) { right } + List(6) { down }
        val states = headings.mapIndexed { i, h ->
            val step = MotionEvent.Step(i + 1L, h, StepSource.STEP_DETECTOR)
            monitor.evaluate(graph, tracker.onMotion(step))
        }
        assertTrue(states.take(20).all { it.level == ConfidenceLevel.HIGH }, "${states.take(20)}")
        assertTrue(states.drop(20).any { it.level == ConfidenceLevel.LOW }, "${states.drop(20)}")
    }
}
