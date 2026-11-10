package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.ConfidenceCheck
import edu.wisc.maplens.model.ConfidenceLevel
import edu.wisc.maplens.model.ConfidenceState
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.MotionEvent
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the recorded EH3 walks through [ParticleTracker] and [FourCheckConfidenceMonitor]
 * (#18), as recorded and with drift injected. Skipped when the traces are absent or are Git
 * LFS pointers (CI checks out without LFS).
 */
class ConfidenceReplayTest {
    private val fixtures = System.getProperty("maplens.fixtures")?.let(::File)
    private val calibratedK = 17.3
    private val seeds = 1..10

    private class Walk(val name: String, val steps: List<MotionEvent.Step>)

    private fun load(): Pair<FloorGraph, List<Walk>>? {
        val root = fixtures ?: return null
        val graph = File(root, "maps/eh-3-elevators-evac.graph.json").takeIf { it.exists() }
            ?.let { FloorGraphJson.decode(it.readText()) } ?: return null
        val walks = listOf("01", "02", "03").mapNotNull {
            val name = "eh-3-elevators-3418-3446-$it"
            val csv = File(root, "traces/$name.csv")
            val json = File(root, "traces/$name.json")
            if (!csv.exists() || !json.exists() ||
                csv.readText().startsWith("version https://git-lfs")
            ) {
                return@mapNotNull null
            }
            val offset = Regex("\"headingOffsetRad\":\\s*(-?[0-9.Ee+-]+)")
                .find(json.readText())!!.groupValues[1].toDouble()
            Walk(name, TraceReplay.replay(csv.reader(), offset).steps)
        }
        return graph to walks
    }

    private fun run(
        graph: FloorGraph,
        steps: List<MotionEvent.Step>,
        seed: Int,
        config: TrackingConfig = TrackingConfig(),
        startK: Double = calibratedK,
    ): List<ConfidenceState> {
        val tracker = ParticleTracker(config, Random(seed))
        tracker.start(graph, graph.startNodeId, steps.first().headingRad, startK)
        val monitor = FourCheckConfidenceMonitor(config)
        return steps.map { monitor.evaluate(graph, tracker.onMotion(it)) }
    }

    private fun summary(states: List<ConfidenceState>): String {
        val firstLow = states.indexOfFirst { it.level == ConfidenceLevel.LOW }
        val counts = ConfidenceCheck.entries.joinToString(" ") { check ->
            "${check.name}=${states.count { check in it.failedChecks }}"
        }
        val timeline = states.joinToString("") { s ->
            when {
                s.level == ConfidenceLevel.HIGH -> "."
                ConfidenceCheck.TURN_FIT in s.failedChecks -> "T"
                ConfidenceCheck.HEADING_FIT in s.failedChecks -> "H"
                ConfidenceCheck.GROUP_SPLIT in s.failedChecks -> "G"
                else -> "S"
            }
        }
        return "first LOW $firstLow, $counts\n    $timeline"
    }

    /** Drift is injected from this step on: after the J6 corner, on the long bottom corridor. */
    private val driftFrom = 50

    /** Rotates every heading from [driftFrom] on, like a gyro drifting or a slipped grip. */
    private fun rotated(steps: List<MotionEvent.Step>, deg: Double) = steps.mapIndexed { i, s ->
        if (i >= driftFrom) s.copy(headingRad = s.headingRad + Math.toRadians(deg)) else s
    }

    /** Turns 90° for 4 steps mid-corridor, where no corridor branches off. */
    private fun falseTurn(steps: List<MotionEvent.Step>) = steps.mapIndexed { i, s ->
        if (i in driftFrom until driftFrom + 4) {
            s.copy(headingRad = s.headingRad + Math.PI / 2)
        } else {
            s
        }
    }

    @Test
    fun `recorded walks raise no heading or turn alarms`() {
        val (graph, walks) = load() ?: return
        for (walk in walks) {
            for (seed in seeds) {
                val states = run(graph, walk.steps, seed)
                if (seed == 1) println("${walk.name} as recorded: ${summary(states)}")
                val alarms = states.count {
                    ConfidenceCheck.HEADING_FIT in it.failedChecks ||
                        ConfidenceCheck.TURN_FIT in it.failedChecks
                }
                assertEquals(0, alarms, "${walk.name} seed $seed: ${summary(states)}")
            }
        }
    }

    @Test
    fun `a 35 degree heading drift is flagged within 10 steps`() {
        val (graph, walks) = load() ?: return
        for (walk in walks) {
            val delays = seeds.map { seed ->
                val states = run(graph, rotated(walk.steps, 35.0), seed)
                if (seed == 1) println("${walk.name} +35° from $driftFrom: ${summary(states)}")
                states.drop(driftFrom)
                    .indexOfFirst { ConfidenceCheck.HEADING_FIT in it.failedChecks }
            }
            println("${walk.name}: HEADING_FIT after $delays steps")
            assertTrue(delays.all { it in 0..10 }, "${walk.name}: $delays")
        }
    }

    @Test
    fun `a turn where the corridor has no branch is flagged`() {
        val (graph, walks) = load() ?: return
        for (walk in walks) {
            for (seed in seeds) {
                val states = run(graph, falseTurn(walk.steps), seed)
                if (seed == 1) println("${walk.name} false turn at $driftFrom: ${summary(states)}")
                // Flagged on the turn, or on the turn back if a wide cloud reached a junction
                // whose branch could explain the first one.
                val window = states.subList(driftFrom, driftFrom + 8)
                assertTrue(
                    window.any { ConfidenceCheck.TURN_FIT in it.failedChecks },
                    "${walk.name} seed $seed: ${summary(states)}",
                )
            }
        }
    }
}
