package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.SignObservation
import edu.wisc.maplens.model.StepSource
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParticleTrackerTest {
    private val right = 0.0
    private val down = PI / 2

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

    private val corridor =
        graph(mapOf("S" to PixelPoint(0.0, 0.0), "E" to PixelPoint(300.0, 0.0)), "S" to "E")

    /** S → J to the right, then J branches up to U, down to D and on to E. */
    private val tee = graph(
        mapOf(
            "S" to PixelPoint(0.0, 0.0),
            "J" to PixelPoint(100.0, 0.0),
            "U" to PixelPoint(100.0, -200.0),
            "D" to PixelPoint(100.0, 200.0),
            "E" to PixelPoint(300.0, 0.0),
        ),
        "S" to "J",
        "J" to "U",
        "J" to "D",
        "J" to "E",
    )

    private fun walk(tracker: ParticleTracker, headings: List<Double>): PoseEstimate {
        var pose = tracker.pose
        headings.forEachIndexed { i, h ->
            pose = tracker.onMotion(MotionEvent.Step(i + 1L, h, StepSource.STEP_DETECTOR))
        }
        return pose
    }

    @Test
    fun `steps along a straight corridor move the estimate by about k each`() {
        val tracker = ParticleTracker(random = Random(1))
        tracker.start(corridor, "S", right, stepLengthPx = 10.0)
        val pose = walk(tracker, List(10) { right })
        assertEquals(100.0, pose.position.x, 20.0)
        assertEquals(0.0, pose.position.y, 1e-6)
        assertEquals("S-E", pose.edgeId)
    }

    @Test
    fun `the estimate advances smoothly on a long straight walk, never jumping ahead`() {
        // A long corridor and real stride noise stretch the cloud over several 2 m groups.
        val long =
            graph(mapOf("S" to PixelPoint(0.0, 0.0), "E" to PixelPoint(2000.0, 0.0)), "S" to "E")
        for (seed in 1..5) {
            val tracker = ParticleTracker(random = Random(seed))
            tracker.start(long, "S", right, stepLengthPx = 17.0)
            var previous = tracker.pose.position.x
            for (step in 1..60) {
                val x = tracker.onMotion(
                    MotionEvent.Step(step.toLong(), right, StepSource.STEP_DETECTOR),
                ).position.x
                // Field test (Nov 12): the heaviest 2 m group hopped 51 px (3 steps) at once.
                assertTrue(
                    x - previous < 1.6 * 17.0,
                    "seed $seed step $step jumped ${x - previous}"
                )
                previous = x
            }
            assertEquals(60 * 17.0, previous, 0.15 * 60 * 17.0)
        }
    }

    @Test
    fun `a turn at a junction puts the estimate on the branch that matches the heading`() {
        val tracker = ParticleTracker(random = Random(2))
        tracker.start(tee, "S", right, stepLengthPx = 10.0)
        val pose = walk(tracker, List(10) { right } + List(8) { down })
        assertEquals("J-D", pose.edgeId)
        assertEquals(100.0, pose.position.x, 1e-6)
        assertEquals(80.0, pose.position.y, 30.0)
        assertTrue(pose.topClusterWeight > 0.8, "top cluster ${pose.topClusterWeight}")
    }

    @Test
    fun `walking past a dead end keeps the estimate at the end of the corridor`() {
        val tracker = ParticleTracker(random = Random(3))
        tracker.start(corridor, "S", right, stepLengthPx = 10.0)
        val pose = walk(tracker, List(50) { right })
        // Never past the wall. Particles still short of it aren't penalised, so they pull
        // the estimate back a little; it stays within one cluster radius of the end.
        assertTrue(pose.position.x in 270.0..300.0, "x ${pose.position.x}")
        assertEquals(0.0, pose.position.y, 1e-6)
    }

    @Test
    fun `a corner pulls the cloud's k toward the true step length`() {
        // True k is 10; the tracker starts 30% long. Long particles reach the corner early,
        // walk it with the wrong heading and lose weight.
        val ell = graph(
            mapOf(
                "S" to PixelPoint(0.0, 0.0),
                "C" to PixelPoint(100.0, 0.0),
                "E" to PixelPoint(100.0, 300.0)
            ),
            "S" to "C",
            "C" to "E",
        )
        val tracker = ParticleTracker(random = Random(4))
        tracker.start(ell, "S", right, stepLengthPx = 13.0)
        val pose = walk(tracker, List(10) { right } + List(10) { down })
        assertTrue(pose.stepLengthPx < 12.0, "k ${pose.stepLengthPx}")
        assertEquals(100.0, pose.position.y, 35.0)
    }

    @Test
    fun `the same seed gives the same estimates`() {
        fun run(): PoseEstimate {
            val tracker = ParticleTracker(random = Random(5))
            tracker.start(tee, "S", right, 10.0)
            return walk(tracker, List(10) { right } + List(5) { down })
        }
        assertEquals(run(), run())
    }

    @Test
    fun `turn events and signs leave the estimate where it is`() {
        val tracker = ParticleTracker(TrackingConfig(signRelocates = false), Random(6))
        tracker.start(corridor, "S", right, 10.0)
        val before = walk(tracker, List(5) { right })
        val after = tracker.onMotion(MotionEvent.Turn(99, PI / 2))
        assertEquals(before.position, after.position)
        assertEquals(99L, after.timestampNanos)
        assertFalse(tracker.onSign(SignObservation(100, "3445", 0.9f, "E")))
        assertEquals(before.position, tracker.pose.position)
    }

    @Test
    fun `start rejects an unusable step length or start node`() {
        val tracker = ParticleTracker(random = Random(7))
        assertFailsWith<IllegalArgumentException> { tracker.start(corridor, "S", right, 0.0) }
        assertFailsWith<IllegalArgumentException> {
            tracker.start(
                corridor,
                "nowhere",
                right,
                10.0
            )
        }
        assertFailsWith<IllegalStateException> {
            ParticleTracker().onMotion(MotionEvent.Step(1, right, StepSource.STEP_DETECTOR))
        }
    }

    @Test
    fun `replay turns a trace back into aligned steps and checkpoints`() {
        // Identity quaternion: phone flat, top edge along the game-rotation frame's +y.
        val csv = """
            timestamp_ns,type,v0,v1,v2,v3,label
            100,game_rv,0.0,0.0,0.0,1.0,
            200,game_rv,0.0,0.0,0.0,1.0,
            250,step,1.0,,,,
            300,checkpoint,,,,,door-3445
            350,accel,0.0,0.0,9.8,,
        """.trimIndent()
        val trace = TraceReplay.replay(csv.reader(), headingOffsetRad = down)
        assertEquals(1, trace.steps.size)
        assertEquals(down, trace.steps.single().headingRad, 1e-9)
        assertEquals(listOf(TraceCheckpoint(300, "door-3445")), trace.checkpoints)
    }
}
