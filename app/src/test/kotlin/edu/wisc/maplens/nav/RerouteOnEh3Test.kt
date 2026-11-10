package edu.wisc.maplens.nav

import edu.wisc.maplens.Demo
import edu.wisc.maplens.guidance.Maneuver
import edu.wisc.maplens.guidance.RerouteParams
import edu.wisc.maplens.guidance.RerouteReason
import edu.wisc.maplens.guidance.RouteGuide
import edu.wisc.maplens.guidance.RoutePath
import edu.wisc.maplens.guidance.RouteProgress
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.routing.AStarRouter
import edu.wisc.maplens.routing.nodesForLabel
import edu.wisc.maplens.routing.roomDestinations
import edu.wisc.maplens.routing.routeToLabel
import edu.wisc.maplens.tracking.ParticleTracker
import edu.wisc.maplens.tracking.TraceReplay
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Off route and rerouting (#80) on the traced EH3 graph: every room with every demo walk, and
 * one real recorded walk that leaves the planned route. Skipped without the fixtures.
 */
class RerouteOnEh3Test {
    private val router = AStarRouter()
    private val params = RerouteParams()
    private val k = Demo.STEP_LENGTH_PX

    private fun graph(): FloorGraph? = listOf("../data/fixtures/maps", "data/fixtures/maps")
        .map { File(it, "eh-3-elevators-evac.graph.json") }
        .firstOrNull { it.exists() }
        ?.let { FloorGraphJson.decode(it.readText()) }

    private fun room(graph: FloorGraph, label: String) = Place(
        key = "room:$label",
        kind = PlaceKind.ROOM,
        label = label,
        nodeIds = graph.nodesForLabel(label),
        position = graph.nodes.getValue(graph.nodesForLabel(label).first()).position,
    )

    private class Outcome(
        val reasons: List<RerouteReason>,
        val arrived: Boolean,
        val saidTurnAround: Boolean,
        val steps: Int,
        val biggestJumpPx: Double,
    )

    /**
     * What the app does with demo poses: track progress, reroute when asked (applied one step
     * later, as the banner shows "Rerouting…" first), and stop on arrival.
     */
    private fun simulate(graph: FloorGraph, label: String, scenario: DemoScenario): Outcome? {
        val place = room(graph, label)
        val first = router.routeToLabel(graph, graph.startNodeId, label) ?: return null
        var guide = RouteGuide(graph, first)
        val walker = DemoWalker(graph, guide.path, scenario, k)
        if (scenario != DemoScenario.FOLLOW && !walker.hasDetour) return null
        var progress = RouteProgress(guide.path, params)
        val reasons = mutableListOf<RerouteReason>()
        var pending: Pair<PoseEstimate, RerouteReason>? = null
        var saidTurnAround = false
        var lastPx = 0.0
        var biggestJump = 0.0
        val limit = (first.lengthPx / k).toInt() * 3 + 60
        repeat(limit) { step ->
            val pose = walker.step(guide.path)
            pending?.let { (at, reason) ->
                val next = router.rerouteTo(graph, place, at, params)
                    ?: fail("$label $scenario: no way from ${at.position}")
                guide = RouteGuide(graph, next.route)
                val start = guide.path.project(at.position)
                progress = RouteProgress(
                    guide.path,
                    params,
                    startPx = start,
                    cooldownSteps = params.cooldownSteps,
                    startFacingBack = next.turnAround,
                )
                saidTurnAround = saidTurnAround || next.turnAround
                reasons += reason
                lastPx = start
                pending = null
            }
            val status = progress.update(pose)
            if (status.facingBack) saidTurnAround = true
            biggestJump = maxOf(biggestJump, abs(status.progressPx - lastPx))
            lastPx = status.progressPx
            status.reroute?.let { pending = pose to it }
            val instruction = guide.instruction(status.progressPx, k)
            if (pending == null && !status.facingBack &&
                instruction.maneuver == Maneuver.ARRIVE && instruction.stepsUntil == 0
            ) {
                return Outcome(reasons, true, saidTurnAround, step + 1, biggestJump)
            }
        }
        return Outcome(reasons, false, saidTurnAround, limit, biggestJump)
    }

    @Test
    fun `following the route never reroutes and always arrives`() {
        val graph = graph() ?: return
        for (label in graph.roomDestinations()) {
            val out = simulate(graph, label, DemoScenario.FOLLOW) ?: continue
            assertTrue(out.arrived, "$label: did not arrive in ${out.steps} steps")
            assertEquals(emptyList(), out.reasons, label)
            assertTrue(!out.saidTurnAround, "$label: said turn around on the route")
            assertTrue(out.biggestJumpPx <= 1.5 * k, "$label: progress jumped ${out.biggestJumpPx}")
        }
    }

    @Test
    fun `a wrong turn reroutes once from the wrong corridor and still arrives`() {
        val graph = graph() ?: return
        var tried = 0
        for (label in graph.roomDestinations()) {
            val out = simulate(graph, label, DemoScenario.WRONG_TURN) ?: continue
            tried++
            assertTrue(out.arrived, "$label: did not arrive (${out.reasons})")
            assertEquals(RerouteReason.OFF_ROUTE, out.reasons.firstOrNull(), label)
            assertTrue(out.reasons.size <= 2, "$label: rerouted ${out.reasons}")
        }
        println("wrong turn: $tried rooms")
        assertTrue(tried >= 20, "only $tried rooms had a wrong turn to take")
    }

    @Test
    fun `walking the wrong way says turn around, reroutes and still arrives`() {
        val graph = graph() ?: return
        var tried = 0
        for (label in graph.roomDestinations()) {
            val out = simulate(graph, label, DemoScenario.WRONG_WAY) ?: continue
            tried++
            assertTrue(out.arrived, "$label: did not arrive (${out.reasons})")
            assertTrue(out.saidTurnAround, "$label: never said turn around")
            assertEquals(RerouteReason.WRONG_WAY, out.reasons.firstOrNull(), label)
            assertTrue(out.reasons.size <= 2, "$label: rerouted ${out.reasons}")
        }
        println("wrong way: $tried rooms")
        assertTrue(tried >= 20, "only $tried rooms were long enough to walk the wrong way")
    }

    /**
     * Recorded walk 01 (START → 3418 → 3446) replayed through the particle tracker while the
     * app navigates to a room whose route leaves the walked one at a junction: no reroute may
     * fire while the walker is still on the shared part, and one must fire soon after the
     * walker carries on past the junction.
     */
    @Test
    fun `a recorded walk that leaves the planned route reroutes soon after the junction`() {
        val fixtures = System.getProperty("maplens.fixtures")?.let(::File) ?: return
        val graph = graph() ?: return
        val csv = File(fixtures, "traces/eh-3-elevators-3418-3446-01.csv")
        val json = File(fixtures, "traces/eh-3-elevators-3418-3446-01.json")
        if (!csv.exists() || csv.readText().startsWith("version https://git-lfs")) return
        val offset = Regex("\"headingOffsetRad\":\\s*(-?[0-9.Ee+-]+)")
            .find(json.readText())!!.groupValues[1].toDouble()
        val trace = TraceReplay.replay(csv.reader(), offset)
        val walked = RoutePath(
            graph,
            checkNotNull(router.routeToLabel(graph, graph.startNodeId, "3446")),
        )
        // A room whose route shares the walk's first 15+ steps, then turns off it.
        val (label, splitPx) = graph.roomDestinations().asSequence().mapNotNull { label ->
            split(graph, walked, label)?.let { label to it }
        }.first()
        val route = checkNotNull(router.routeToLabel(graph, graph.startNodeId, label))
        for (seed in 1..5) {
            val tracker = ParticleTracker(random = Random(seed))
            tracker.start(graph, graph.startNodeId, trace.steps.first().headingRad, k)
            val progress = RouteProgress(RoutePath(graph, route), params)
            var firstAt: Double? = null
            for (step in trace.steps) {
                val pose = tracker.onMotion(step)
                val status = progress.update(pose)
                if (status.reroute != null) {
                    firstAt = walked.project(pose.position)
                    break
                }
            }
            val at = firstAt ?: fail("seed $seed: walked off the route to $label, no reroute")
            val late = (at - splitPx) / k
            println(
                "seed $seed: route to $label splits at ${"%.0f".format(splitPx)} px, " +
                    "rerouted at ${"%.0f".format(at)} px (${"%.1f".format(late)} steps)",
            )
            assertTrue(at > splitPx, "seed $seed: rerouted before the split ($at < $splitPx)")
            assertTrue(
                at - splitPx <= 12 * k,
                "seed $seed: rerouted ${(at - splitPx) / k} steps late"
            )
        }
    }

    /**
     * Where along [walked] the route to [label] leaves it, if it shares the walk's first 15+
     * steps, turns off well before the walk ends, and the room's doors aren't on the walk.
     */
    private fun split(graph: FloorGraph, walked: RoutePath, label: String): Double? {
        val route = router.routeToLabel(graph, graph.startNodeId, label) ?: return null
        val shared = route.edgeIds.zip(walked.route.edgeIds).takeWhile { (a, b) -> a == b }
        if (shared.size == route.edgeIds.size) return null
        val node = route.nodeIds[shared.size]
        val at = walked.distances[walked.nodeAt.entries.first { it.value == node }.key]
        if (at < 15 * k || at > walked.lengthPx - 25 * k) return null
        if (graph.nodesForLabel(label).any { it in walked.route.nodeIds }) return null
        return at
    }
}
