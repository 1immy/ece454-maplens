package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.SignObservation
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-anchoring (#19) on the recorded EH3 walks. The walker tapped a checkpoint at each door
 * (3418, then 3446), so a sign read at the 3418 tap is exactly what a scan there would give.
 * Skipped when the traces are absent or are Git LFS pointers.
 */
class SignReanchorReplayTest {
    private val fixtures = System.getProperty("maplens.fixtures")?.let(::File)
    private val k = 17.3
    private val pxPerMeter = k / 0.7
    private val seeds = 1..10

    private class Walk(val name: String, val trace: ReplayedTrace)

    private fun load(): Pair<FloorGraph, List<Walk>>? {
        val root = fixtures ?: return null
        val graph = File(root, "maps/eh-3-elevators-evac.graph.json").takeIf { it.exists() }
            ?.let { FloorGraphJson.decode(it.readText()) } ?: return null
        val walks = listOf("01", "02", "03").mapNotNull {
            val name = "eh-3-elevators-3418-3446-$it"
            val csv = File(root, "traces/$name.csv")
            val json = File(root, "traces/$name.json")
            if (!csv.exists() || csv.readText().startsWith("version https://git-lfs")) {
                return@mapNotNull null
            }
            val offset = Regex("\"headingOffsetRad\":\\s*(-?[0-9.Ee+-]+)")
                .find(json.readText())!!.groupValues[1].toDouble()
            Walk(name, TraceReplay.replay(csv.reader(), offset))
        }
        return graph to walks
    }

    /** Error (px) to door [label] just before the tap at it, and at the end (3446). */
    private class Run(val atTapPx: Double, val endPx: Double, val sign: SignResult?)

    private fun run(
        graph: FloorGraph,
        walk: Walk,
        seed: Int,
        config: TrackingConfig,
        startK: Double,
        signText: String?,
    ): Run {
        val door = graph.landmarks.associate { it.label to it.nodeId }
        val tracker = ParticleTracker(config, Random(seed))
        tracker.start(graph, graph.startNodeId, walk.trace.steps.first().headingRad, startK)
        val tap = walk.trace.checkpoints.first { it.label == "door-3418" }.timestampNs
        var atTap: Double? = null
        var sign: SignResult? = null
        for (step in walk.trace.steps) {
            if (atTap == null && step.timestampNanos > tap) {
                if (signText != null) {
                    val read = SignObservation(tap, signText, 0.9f, null)
                    sign = tracker.onSighting(SignSighting(read))
                }
                val at3418 = graph.nodes.getValue(door.getValue("3418")).position
                atTap = tracker.pose.position.distanceTo(at3418)
            }
            tracker.onMotion(step)
        }
        val end = tracker.pose.position.distanceTo(
            graph.nodes.getValue(door.getValue("3446")).position
        )
        return Run(atTap ?: Double.NaN, end, sign)
    }

    private fun List<Double>.median() = sorted()[size / 2]

    private fun m(px: Double) = "%.1f m".format(px / pxPerMeter)

    @Test
    fun `refining k at turns does not make the recorded walks worse`() {
        val (graph, walks) = load() ?: return
        for (walk in walks) {
            val (on, off) = listOf(true, false).map { refine ->
                seeds.map {
                    run(graph, walk, it, TrackingConfig(landmarkStepLength = refine), k, null).endPx
                }
            }
            println(
                "${walk.name}: end error with turn anchors ${m(
                    on.median()
                )}, without ${m(off.median())}"
            )
            assertTrue(on.median() <= off.median() + 0.5 * pxPerMeter, walk.name)
        }
    }

    @Test
    fun `a sign read at 3418 pulls a drifted estimate back onto the door`() {
        val (graph, walks) = load() ?: return
        // Drift: k 15% too long and no START calibration, so the estimate runs ahead.
        val drifted = TrackingConfig(autoCalibrateStepLength = false)
        for (walk in walks) {
            val without = seeds.map { run(graph, walk, it, drifted, 1.15 * k, null) }
            val with = seeds.map { run(graph, walk, it, drifted, 1.15 * k, "3418") }
            val afterSign = with.map { it.sign }
            assertTrue(
                afterSign.all { it?.accepted == true },
                "${walk.name}: ${afterSign.map { it?.reason }}"
            )
            val endWithout = without.map { it.endPx }.median()
            val endWith = with.map { it.endPx }.median()
            val spread = afterSign.map { it!!.spreadAfterPx }.median()
            println(
                "${walk.name}: drifted end error ${m(endWithout)} -> ${m(endWith)} " +
                    "with a sign at 3418; " +
                    "spread after sign ${m(spread)}",
            )
            // Re-anchoring fixes position at once; k is only nudged (0.7 old / 0.3 new, A4),
            // so the end may still drift, but not clearly worse than without the sign. Without
            // it the drifted cloud is stopped by the corridor's end near 3446, which bounds that
            // error too, so the two land within half a metre (5.2 m vs 5.4 m on walk 01).
            assertTrue(endWith <= endWithout + 0.5 * pxPerMeter, walk.name)
            assertTrue(spread < 2 * pxPerMeter, "${walk.name}: spread after sign ${m(spread)}")
        }
    }

    @Test
    fun `a sign for a door far down the corridor is rejected by the A5 gate`() {
        val (graph, walks) = load() ?: return
        for (walk in walks) {
            for (seed in 1..3) {
                val gated = TrackingConfig(signRelocates = false)
                val r = run(graph, walk, seed, gated, k, "3446")
                assertEquals(SignOutcome.TOO_FAR, r.sign?.reason, "${walk.name} seed $seed")
                // By default the same read moves the cloud there, and says it did.
                val moved = run(graph, walk, seed, TrackingConfig(), k, "3446").sign
                assertTrue(moved?.accepted == true && moved.relocated, "${walk.name} $moved")
            }
        }
    }
}
