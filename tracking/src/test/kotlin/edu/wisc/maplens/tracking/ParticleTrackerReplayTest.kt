package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.nearestEdge
import java.io.File
import java.util.PriorityQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Replays the recorded EH3 walks (`data/fixtures/traces`) through [ParticleTracker] (#13).
 * Skipped when the traces are absent or are Git LFS pointers (CI checks out without LFS).
 */
class ParticleTrackerReplayTest {
    private val fixtures = System.getProperty("maplens.fixtures")?.let(::File)

    /** Calibrated from walks 01 and 02 (route px / detector steps); walk 03 is held out. */
    private val calibratedK = 17.3

    /** Pixels per metre on this map: k over the 0.7 m nominal stride. */
    private val pxPerMeter = calibratedK / 0.7

    /** About 1 m: the estimate must stay on the walked corridors (#13 "done when"). */
    private val onCorridorTolerancePx = 1.0 * pxPerMeter

    /**
     * Regression guard, not a target. End error follows from k, which the cloud learns from the
     * corners before 3418; extra detector steps there (walk 03: 35 vs 32 counted) shorten it,
     * and nothing on the straight corridor corrects it until signs re-anchor (#19).
     */
    private val maxEndErrorPx = 5.0 * pxPerMeter

    @Test
    fun `EH3 walks stay on the walked corridors and end near 3446`() {
        val root = fixtures ?: return
        val graph = File(root, "maps/eh-3-elevators-evac.graph.json").takeIf { it.exists() }
            ?.let { FloorGraphJson.decode(it.readText()) } ?: return
        val door = graph.landmarks.associate { "door-${it.label}" to it.nodeId }
        val route = shortestPath(graph, graph.startNodeId, door.getValue("door-3418")) +
            shortestPath(graph, door.getValue("door-3418"), door.getValue("door-3446"))
        val routeGraph = graph.copy(edges = route)
        val walks = listOf("01", "02", "03").map { "eh-3-elevators-3418-3446-$it" }
        for (name in walks) {
            val csv = File(root, "traces/$name.csv")
            val json = File(root, "traces/$name.json")
            val isPointer = csv.exists() && csv.readText().startsWith("version https://git-lfs")
            if (!csv.exists() || !json.exists() || isPointer) {
                continue
            }
            val offset = Regex("\"headingOffsetRad\":\\s*(-?[0-9.Ee+-]+)")
                .find(json.readText())!!.groupValues[1].toDouble()
            val trace = TraceReplay.replay(csv.reader(), offset)
            val tracker = ParticleTracker(random = Random(454))
            tracker.start(graph, graph.startNodeId, trace.steps.first().headingRad, calibratedK)

            var worstOffRoute = 0.0
            val checkpointErrors = mutableListOf<String>()
            var pending = trace.checkpoints
            for (step in trace.steps) {
                // Score each checkpoint against the estimate just before the tap.
                while (pending.isNotEmpty() && pending.first().timestampNs < step.timestampNanos) {
                    checkpointErrors += error(graph, door, pending.first(), tracker)
                    pending = pending.drop(1)
                }
                val pose = tracker.onMotion(step)
                val offRoute = routeGraph.nearestEdge(pose.position)!!.distancePx
                worstOffRoute = maxOf(worstOffRoute, offRoute)
            }
            pending.forEach { checkpointErrors += error(graph, door, it, tracker) }
            val end = graph.nodes.getValue(door.getValue("door-3446")).position
            val endError = tracker.pose.position.distanceTo(end)
            println(
                "$name: ${trace.steps.size} steps, " +
                    "worst off-route ${"%.1f".format(worstOffRoute)} px, " +
                    "end error ${"%.1f".format(endError)} px " +
                    "(${"%.1f".format(endError / pxPerMeter)} m), " +
                    "k ${"%.1f".format(tracker.pose.stepLengthPx)}, checkpoints: $checkpointErrors",
            )
            assertTrue(
                worstOffRoute <= onCorridorTolerancePx,
                "$name left the route by $worstOffRoute px"
            )
            assertTrue(endError <= maxEndErrorPx, "$name ended $endError px from 3446")
        }
    }

    private fun error(
        graph: FloorGraph,
        door: Map<String, String>,
        cp: TraceCheckpoint,
        tracker: ParticleTracker,
    ): String {
        val at = graph.nodes.getValue(door.getValue(cp.label)).position
        val distance = tracker.pose.position.distanceTo(at)
        return "${cp.label.removePrefix("door-")} ${"%.0f".format(distance)} px " +
            "(k ${"%.1f".format(tracker.pose.stepLengthPx)})"
    }

    /** Dijkstra over edge lengths; returns the edges from [from] to [to] in order. */
    private fun shortestPath(graph: FloorGraph, from: String, to: String) = buildList {
        val adjacency = graph.adjacency()
        val distance = mutableMapOf(from to 0.0)
        val via = mutableMapOf<String, Pair<edu.wisc.maplens.model.Edge, String>>()
        val queue = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        queue += 0.0 to from
        while (queue.isNotEmpty()) {
            val (d, node) = queue.poll()
            if (node == to) break
            if (d > distance.getValue(node)) continue
            for ((edge, next) in adjacency[node].orEmpty()) {
                val nd = d + edge.lengthPx
                if (nd < (distance[next] ?: Double.MAX_VALUE)) {
                    distance[next] = nd
                    via[next] = edge to node
                    queue += nd to next
                }
            }
        }
        var node = to
        while (node != from) {
            val (edge, previous) = via.getValue(node)
            add(0, edge)
            node = previous
        }
    }
}
