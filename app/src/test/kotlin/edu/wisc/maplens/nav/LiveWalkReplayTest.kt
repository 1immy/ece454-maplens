package edu.wisc.maplens.nav

import edu.wisc.maplens.guidance.RouteGuide
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.routeToLabel
import edu.wisc.maplens.tracking.ParticleTracker
import edu.wisc.maplens.tracking.SensorMotionProcessor
import edu.wisc.maplens.tracking.WalkingGate
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test

/**
 * Developer tool: replays a live walk recorded by the app (`sessions/trace-*.csv`) through the
 * same pipeline as live navigation (processor → walking gate → particle tracker → [PuckMotion])
 * at 60 frames per second, and prints every frame where the dot jumps and every tracker jump.
 *
 * Runs only when given a walk: `MAPLENS_WALK=<trace.csv>` (and optionally
 * `MAPLENS_WALK_FACING=<map bearing rad>` from the session log's start row, and
 * `MAPLENS_WALK_ROOM=<label>`), e.g. `./gradlew :app:testDebugUnitTest --tests '*LiveWalkReplay*'`.
 */
class LiveWalkReplayTest {
    @Test
    fun `replay a recorded live walk`() {
        val walk = System.getProperty("maplens.walk")?.let(::File)?.takeIf { it.exists() } ?: return
        val fixtures = File(System.getProperty("maplens.fixtures") ?: return)
        val graph = FloorGraphJson.decode(
            File(fixtures, "maps/eh-3-elevators-evac.graph.json").readText(),
        )
        val facing = System.getProperty("maplens.walk.facing")?.toDouble() ?: PI
        val room = System.getProperty("maplens.walk.room") ?: "3418"
        val route = checkNotNull(AStarRouter().routeToLabel(graph, graph.startNodeId, room))
        val guide = RouteGuide(graph, route)
        val k = 17.3
        val rows = walk.readLines().drop(1).map { it.split(',') }
            .filter { it.size > 2 && it[0].isNotEmpty() }.sortedBy { it[0].toLong() }
        val processor = SensorMotionProcessor().apply {
            if (System.getProperty("maplens.walk.detectorOnly") == null) selectFused()
        }
        val gate = WalkingGate()
        val tracker = ParticleTracker(random = Random(1))
        val motion = PuckMotion(snapPx = 4 * k)
        var started = false
        val t0 = rows.first()[0].toLong()
        var frameNs = t0
        var row = 0
        var lastS: Double? = null
        val trail = Trail(graph.nodes.getValue(graph.startNodeId).position)
        var lastAlong: Double? = null
        var accepted = 0
        val pending = mutableListOf<edu.wisc.maplens.model.MotionEvent.Step>()
        var rejected = 0
        while (row < rows.size) {
            val frameSec = (frameNs - t0) / 1e9
            while (row < rows.size && rows[row][0].toLong() <= frameNs) {
                val r = rows[row++]
                val t = r[0].toLong()
                val v = { n: Int -> FloatArray(n) { r.getOrNull(2 + it)?.toFloatOrNull() ?: 0f } }
                when (r[1]) {
                    "game_rv" -> {
                        processor.rotation(t, v(4), 0)
                        gate.rotation(t, v(4))
                        if (!started && processor.heading != null && processor.align(facing, t)) {
                            tracker.start(graph, graph.startNodeId, facing, k)
                            motion.reset(0.0)
                            started = true
                        }
                    }
                    "accel" -> {
                        gate.acceleration(t, v(3))
                        processor.acceleration(t, v(3))?.let { pending += it }
                    }
                    "gyro" -> gate.gyroscope(t, v(3))
                    "step" -> processor.detector(t)?.let { pending += it }
                }
                for (step in pending) {
                    val t = step.timestampNanos
                    run {
                        val passed = gate.offer(step)
                        if (passed.isEmpty()) {
                            rejected++
                            println("%7.2f  step REJECTED %s".format(frameSec, gate.lastVerdict))
                        }
                        for (s in passed) {
                            accepted++
                            val pose = tracker.onMotion(s)
                            val along = guide.progressPx(pose)
                            lastAlong?.let { prev ->
                                if (abs(along - prev) > 2 * k) {
                                    println(
                                        "%7.2f  TRACKER JUMP along %.0f -> %.0f px (%.1f steps)"
                                            .format(frameSec, prev, along, (along - prev) / k),
                                    )
                                }
                            }
                            lastAlong = along
                            val walked = trail.add(pose.position)
                            motion.onStep(walked, frameSec, k, t)
                        }
                    }
                }
                pending.clear()
            }
            if (started) {
                motion.setMoving(gate.moving(frameNs))
                val s = motion.advance(frameSec)
                lastS?.let { prev ->
                    if (abs(s - prev) > 3.0) {
                        println("%7.2f  DOT JUMP %.1f px in one frame".format(frameSec, s - prev))
                    }
                }
                lastS = s
            }
            frameNs += 16_666_667L
        }
        println("accepted $accepted, rejected $rejected, dot ends at ${lastS?.toInt()} px")
    }
}
