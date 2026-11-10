package edu.wisc.maplens.nav

import edu.wisc.maplens.guidance.RoutePath
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.bearing
import edu.wisc.maplens.model.bearingDifference
import edu.wisc.maplens.model.corridorsAt
import edu.wisc.maplens.model.edge
import edu.wisc.maplens.model.edgePoints

/** What the demo walker does, so rerouting can be shown without walking the building. */
enum class DemoScenario {
    /** Walks the route as given. */
    FOLLOW,

    /** Misses a turn: goes down a corridor the route doesn't use, then follows the new route. */
    WRONG_TURN,

    /** Sets off the right way, turns round and walks back, then follows the new route. */
    WRONG_WAY,
}

/**
 * A scripted walker for Demo mode (#80). It reports a perfect [PoseEstimate] each step, like
 * the old fake pose stream, but can first walk a detour ([DemoScenario]); after that it
 * follows whichever route is active, so it picks up a reroute the way a person would.
 *
 * Pure Kotlin so the scenarios unit-test on the EH3 graph.
 */
class DemoWalker(
    graph: FloorGraph,
    route: RoutePath,
    val scenario: DemoScenario,
    private val stepLengthPx: Double,
    private val stepIntervalNanos: Long = 600_000_000,
) {
    init {
        require(stepLengthPx > 0) { "step length must be positive" }
    }

    /** The scripted part, walked before following the active route; empty for FOLLOW. */
    private val detour: Polyline? = when (scenario) {
        DemoScenario.FOLLOW -> null
        DemoScenario.WRONG_TURN -> wrongTurn(graph, route)
        DemoScenario.WRONG_WAY -> wrongWay(route)
    }

    /** Whether the scenario found somewhere to go wrong (a junction with a spare corridor). */
    val hasDetour: Boolean get() = detour != null

    private var detourPx = 0.0
    private var following = detour == null
    private var followPath: RoutePath? = null
    private var followPx = 0.0
    private var position = route.points.first()
    private var heading = route.bearingAt(0.0)

    var stepsTaken = 0
        private set

    /** Done once it reached the end of [active] while following it. */
    fun finished(active: RoutePath): Boolean =
        following && followPath === active && followPx >= active.lengthPx - 1e-6

    fun current(): PoseEstimate = PoseEstimate(
        timestampNanos = stepsTaken * stepIntervalNanos,
        position = position,
        headingRad = heading,
        edgeId = null,
        spreadPx = 0.0,
        topClusterWeight = 1.0,
        stepLengthPx = stepLengthPx,
    )

    /** Takes one step: along the detour while it lasts, then along [active]. */
    fun step(active: RoutePath): PoseEstimate {
        val d = detour
        if (!following && d != null) {
            detourPx += stepLengthPx
            if (detourPx >= d.length) {
                detourPx = d.length
                following = true
            }
            position = d.pointAt(detourPx)
            heading = d.bearingAt(detourPx)
            stepsTaken++
            return current()
        }
        if (followPath !== active) {
            // A new route (or the first): pick it up from where the walker stands.
            followPath = active
            followPx = active.project(position)
        }
        if (followPx < active.lengthPx) {
            followPx = minOf(followPx + stepLengthPx, active.lengthPx)
            stepsTaken++
        }
        position = active.pointAt(followPx)
        heading = active.bearingAt(minOf(followPx, active.lengthPx - 1e-6).coerceAtLeast(0.0))
        return current()
    }

    /**
     * The route up to the first node where it can go wrong, then [WRONG_TURN_STEPS] steps down
     * a corridor the route doesn't use: preferably the one closest to straight on (missing a
     * turn), at a node a few steps in, on a corridor long enough to be clearly off route.
     */
    private fun wrongTurn(graph: FloorGraph, route: RoutePath): Polyline? {
        val used = route.route.edgeIds.toSet()
        val minStart = MIN_STEPS_BEFORE * stepLengthPx
        for ((index, nodeId) in route.nodeAt.toSortedMap()) {
            val at = route.distances[index]
            if (at < minStart || index == route.points.lastIndex) continue
            val incoming = bearing(route.points[index - 1], route.points[index])
            val exit = graph.corridorsAt(nodeId)
                .filter { it.edgeId !in used }
                .filter { graph.edge(it.edgeId).lengthPx >= MIN_WRONG_STEPS * stepLengthPx }
                .minByOrNull { bearingDifference(it.bearingRad, incoming) }
                ?: continue
            val e = graph.edge(exit.edgeId)
            val drawn = graph.edgePoints(e).let { if (e.from == nodeId) it else it.reversed() }
            val prefix = route.points.subList(0, index + 1)
            val branch = Polyline(drawn).cut(WRONG_TURN_STEPS * stepLengthPx)
            return Polyline(prefix + branch.drop(1))
        }
        return null
    }

    /** Sets off along the route, turns round and walks back [WRONG_WAY_STEPS] steps. */
    private fun wrongWay(route: RoutePath): Polyline? {
        val out = minOf(
            maxOf(MIN_STEPS_BEFORE * stepLengthPx, WRONG_WAY_SHARE * route.lengthPx),
            route.lengthPx - 2 * stepLengthPx,
        )
        if (out < 2 * stepLengthPx) return null
        val back = minOf(WRONG_WAY_STEPS * stepLengthPx, out)
        val ahead = Polyline(route.points).cut(out)
        val returning = Polyline(ahead.reversed()).cut(back)
        return Polyline(ahead + returning.drop(1))
    }

    /** A walkable polyline with arc length. */
    private class Polyline(points: List<PixelPoint>) {
        val points = points.fold(mutableListOf<PixelPoint>()) { acc, p ->
            if (acc.isEmpty() || acc.last() != p) acc += p
            acc
        }
        private val cumulative = this.points.indices.runningFold(0.0) { acc, i ->
            if (i == 0) acc else acc + this.points[i - 1].distanceTo(this.points[i])
        }.drop(1)
        val length: Double get() = cumulative.last()

        private fun segment(s: Double): Int {
            val i = cumulative.indexOfFirst { it > s }
            return when {
                points.size < 2 -> -1
                i <= 0 -> if (i == 0) 0 else points.lastIndex - 1
                else -> i - 1
            }
        }

        fun pointAt(s: Double): PixelPoint {
            val i = segment(s)
            if (i < 0) return points.first()
            val a = points[i]
            val b = points[i + 1]
            val t = ((s - cumulative[i]) / (cumulative[i + 1] - cumulative[i])).coerceIn(0.0, 1.0)
            return PixelPoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
        }

        fun bearingAt(s: Double): Double {
            val i = segment(minOf(s, length - 1e-6).coerceAtLeast(0.0))
            return if (i < 0) 0.0 else bearing(points[i], points[i + 1])
        }

        /** The first [s] px of the polyline. */
        fun cut(s: Double): List<PixelPoint> {
            if (s >= length) return points
            val i = segment(s)
            return points.subList(0, i + 1) + pointAt(s)
        }
    }

    private companion object {
        /** Walk at least this far along the route before going wrong. */
        const val MIN_STEPS_BEFORE = 4.0

        /** A wrong corridor must be this long to get clearly off route. */
        const val MIN_WRONG_STEPS = 6.0

        /** Steps down the wrong corridor before the walker follows the new route. */
        const val WRONG_TURN_STEPS = 9.0

        /** Wrong way: set off this share of the route (at least [MIN_STEPS_BEFORE])... */
        const val WRONG_WAY_SHARE = 0.3

        /** ...then walk back this many steps. */
        const val WRONG_WAY_STEPS = 7.0
    }
}
