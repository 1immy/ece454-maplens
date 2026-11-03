package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.SignObservation
import edu.wisc.maplens.model.bearingDifference
import edu.wisc.maplens.model.bearingOnEdge
import edu.wisc.maplens.model.corridorsAt
import edu.wisc.maplens.model.nearestEdge
import edu.wisc.maplens.model.pointOnEdge
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Corridor-constrained particle filter (#13; `project-AI-context.md`, A3).
 *
 * Each particle is a guess at where the walker is: an edge, an offset along it, a walking
 * direction, and its own step length k. Particles never leave the corridor graph, so the
 * map itself rules out walking through walls. Per step:
 * 1. **Predict:** every particle walks its own k (±`stepLengthNoise`) along its corridor in
 *    the direction that best matches the step heading. At a node it picks a next corridor at
 *    random, favouring branches whose bearing fits the heading.
 * 2. **Weigh:** `w ← w · exp(−(θ − β)² / 2σθ²)`, where θ is the step heading and β the
 *    corridor bearing the particle is now walking. Walking past a dead end costs
 *    `deadEndWeight`. Weights are kept as logs so long walks can't underflow.
 * 3. **Resample** (systematic) when the effective particle count drops below
 *    `resampleFraction · N`.
 * 4. **Estimate:** group particles within `clusterRadiusMeters` and report the weighted mean
 *    of the heaviest group, snapped onto the graph. Groups are never averaged together, so
 *    two candidate corridors can't produce an estimate in the wall between them.
 *
 * Particles whose k is too long reach a corner before the walker turns and are penalised for
 * the wrong bearing; this is how the cloud corrects k over a route.
 *
 * Sign re-anchoring ([onSign]) is #19 and not implemented yet. Not thread safe: one
 * navigation worker owns it. Pass a seeded [random] for reproducible tests.
 */
class ParticleTracker(
    private val config: TrackingConfig = TrackingConfig(),
    private val random: Random = Random.Default,
) : Tracker {
    /** [forward] means walking from edge.from toward edge.to. */
    private class Particle(
        var edge: Edge,
        var offsetPx: Double,
        var forward: Boolean,
        val stepLengthPx: Double,
        var logWeight: Double = 0.0,
    ) {
        fun copy() = Particle(edge, offsetPx, forward, stepLengthPx)
    }

    private lateinit var graph: FloorGraph
    private lateinit var adjacency: Map<String, List<Pair<Edge, String>>>
    private var particles = listOf<Particle>()
    private val sigmaRad get() = Math.toRadians(config.headingSigmaDeg)
    private lateinit var last: PoseEstimate

    /** Latest estimate; valid after [start]. */
    val pose: PoseEstimate get() = last

    override fun start(
        graph: FloorGraph,
        startNodeId: String,
        initialHeadingRad: Double,
        stepLengthPx: Double,
    ) {
        require(stepLengthPx > 0 && stepLengthPx.isFinite()) { "step length must be positive" }
        require(initialHeadingRad.isFinite())
        this.graph = graph
        adjacency = graph.adjacency()
        val options = graph.corridorsAt(startNodeId)
        require(options.isNotEmpty()) { "start node '$startNodeId' has no corridors" }
        val edgesById = graph.edges.associateBy { it.id }
        particles = List(config.particleCount) {
            // Start at the START node, on a corridor that fits the direction the user faces.
            val option = options[pickByHeading(options.map { it.bearingRad }, initialHeadingRad)]
            val edge = edgesById.getValue(option.edgeId)
            val forward = option.towardNodeId == edge.to
            val k = stepLengthPx * (1 + config.initialStepLengthSpread * gaussian())
            val offset = if (forward) 0.0 else edge.lengthPx
            Particle(edge, offset, forward, k.coerceAtLeast(0.5 * stepLengthPx))
        }
        last = estimate(0L, initialHeadingRad)
    }

    override fun onMotion(event: MotionEvent): PoseEstimate {
        check(::graph.isInitialized) { "call start() first" }
        // Turn events add nothing yet: every step already carries its absolute heading.
        val step = event as? MotionEvent.Step
            ?: return last.copy(timestampNanos = event.timestampNanos)
        val heading = step.headingRad
        for (p in particles) {
            // Face whichever way along the current corridor best matches the step.
            val ahead = graph.bearingOnEdge(p.edge.id, p.offsetPx, towardTo = true)
            p.forward = bearingDifference(heading, ahead) <= Math.PI / 2
            val noise = 1 + config.stepLengthNoise * gaussian()
            val distance = (p.stepLengthPx * noise).coerceAtLeast(0.0)
            advance(p, distance, heading)
            val walked = graph.bearingOnEdge(p.edge.id, p.offsetPx, towardTo = p.forward)
            val error = bearingDifference(heading, walked)
            p.logWeight -= error * error / (2 * sigmaRad * sigmaRad)
        }
        normalise()
        if (effectiveCount() < config.resampleFraction * particles.size) resample()
        last = estimate(step.timestampNanos, heading)
        return last
    }

    /** Re-anchoring on a scanned sign arrives with #19; until then signs are ignored. */
    override fun onSign(observation: SignObservation): Boolean = false

    /** Moves [p] [distance] px along the graph, choosing a branch at each node it reaches. */
    private fun advance(p: Particle, distance: Double, heading: Double) {
        var left = distance
        // Bounded: short door segments (5–10 px) can mean several nodes in one step.
        repeat(MAX_NODES_PER_STEP) {
            val room = if (p.forward) p.edge.lengthPx - p.offsetPx else p.offsetPx
            if (left <= room) {
                p.offsetPx += if (p.forward) left else -left
                return
            }
            left -= room
            val node = if (p.forward) p.edge.to else p.edge.from
            // Every corridor out of the node except the one just walked.
            val exits = adjacency[node].orEmpty().filter { it.first.id != p.edge.id }
            if (exits.isEmpty()) {
                // Dead end: stop at the wall, and make this guess unlikely.
                p.offsetPx = if (p.forward) p.edge.lengthPx else 0.0
                p.logWeight += ln(config.deadEndWeight)
                return
            }
            val bearings = exits.map { (edge, _) ->
                if (edge.from == node) {
                    graph.bearingOnEdge(edge.id, 0.0, towardTo = true)
                } else {
                    graph.bearingOnEdge(edge.id, edge.lengthPx, towardTo = false)
                }
            }
            val next = exits[pickByHeading(bearings, heading)].first
            p.edge = next
            p.forward = next.from == node
            p.offsetPx = if (p.forward) 0.0 else next.lengthPx
        }
    }

    /** Samples an index with probability ∝ exp(−Δ² / 2σθ²) of its bearing's heading error. */
    private fun pickByHeading(bearings: List<Double>, heading: Double): Int {
        val scores = bearings.map {
            val d = bearingDifference(heading, it)
            exp(-d * d / (2 * sigmaRad * sigmaRad))
        }
        val total = scores.sum()
        // Every option far off: take the closest rather than divide by ~0.
        if (total < 1e-300) {
            return bearings.indices.minBy { bearingDifference(heading, bearings[it]) }
        }
        var u = random.nextDouble() * total
        for ((i, s) in scores.withIndex()) {
            u -= s
            if (u <= 0) return i
        }
        return scores.lastIndex
    }

    /** Shifts log weights so the largest is 0, keeping exp() in range. */
    private fun normalise() {
        val max = particles.maxOf { it.logWeight }
        for (p in particles) p.logWeight -= max
    }

    private fun weights(): DoubleArray {
        val raw = DoubleArray(particles.size) { exp(particles[it].logWeight) }
        val total = raw.sum()
        for (i in raw.indices) raw[i] /= total
        return raw
    }

    /** 1 / Σw²: N when all weights are equal, 1 when one particle holds everything. */
    private fun effectiveCount(): Double = 1 / weights().sumOf { it * it }

    /** Systematic resampling: one random offset, then evenly spaced picks; low variance. */
    private fun resample() {
        val w = weights()
        val n = particles.size
        val next = ArrayList<Particle>(n)
        var cumulative = w[0]
        var i = 0
        val start = random.nextDouble() / n
        for (j in 0 until n) {
            val u = start + j.toDouble() / n
            while (u > cumulative && i < n - 1) cumulative += w[++i]
            next += particles[i].copy()
        }
        particles = next
    }

    private fun estimate(timestampNanos: Long, heading: Double): PoseEstimate {
        val w = weights()
        val points = particles.map { graph.pointOnEdge(it.edge.id, it.offsetPx) }
        val k = particles.indices.sumOf { w[it] * particles[it].stepLengthPx }
        val radius = config.clusterRadiusMeters * k / config.nominalStrideMeters
        // Greedy clustering, heaviest particles first, so each group grows around a strong seed.
        val seeds = mutableListOf<PixelPoint>()
        val members = mutableListOf<MutableList<Int>>()
        for (i in particles.indices.sortedByDescending { w[it] }) {
            val c = seeds.indexOfFirst { it.distanceTo(points[i]) <= radius }
            if (c >= 0) {
                members[c] += i
            } else {
                seeds += points[i]
                members += mutableListOf(i)
            }
        }
        val top = members.maxBy { group -> group.sumOf { w[it] } }
        val topWeight = top.sumOf { w[it] }
        val centre = PixelPoint(
            top.sumOf { w[it] * points[it].x } / topWeight,
            top.sumOf { w[it] * points[it].y } / topWeight,
        )
        // The mean of a group rounding a corner can sit off the corridor; snap it back on.
        val snapped = graph.nearestEdge(centre)
        val meanX = particles.indices.sumOf { w[it] * points[it].x }
        val meanY = particles.indices.sumOf { w[it] * points[it].y }
        val spread = sqrt(
            particles.indices.sumOf {
                val dx = points[it].x - meanX
                val dy = points[it].y - meanY
                w[it] * (dx * dx + dy * dy)
            }
        )
        return PoseEstimate(
            timestampNanos = timestampNanos,
            position = snapped?.point ?: centre,
            headingRad = heading,
            edgeId = snapped?.edgeId,
            spreadPx = spread,
            topClusterWeight = topWeight,
            stepLengthPx = k,
        )
    }

    /** Standard normal sample (Box–Muller); kotlin.random has no Gaussian. */
    private fun gaussian(): Double {
        val u = 1.0 - random.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * Math.PI * random.nextDouble())
    }

    private companion object {
        const val MAX_NODES_PER_STEP = 100
    }
}
