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
 * the wrong bearing; this is how the cloud corrects k over a route. That only picks among the
 * k values drawn at start, so a stride outside that band stays wrong. With
 * `autoCalibrateStepLength`, a [StepLengthCalibrator] measures k on the legs out of START, once
 * (#12): every particle's k is re-drawn around it (`calibratedStepLengthSpread`), and a share
 * of the cloud (`calibrationReplaceFraction`) is re-placed just past the turn the calibration
 * ended on. The rest stays where it was, so a turn placed on the wrong branch can still lose
 * to the particles that disagree with it.
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
        var stepLengthPx: Double,
        var logWeight: Double = 0.0,
    ) {
        fun copy() = Particle(edge, offsetPx, forward, stepLengthPx)
    }

    private lateinit var graph: FloorGraph
    private lateinit var adjacency: Map<String, List<Pair<Edge, String>>>
    private var particles = listOf<Particle>()
    private val sigmaRad get() = Math.toRadians(config.headingSigmaDeg)
    private lateinit var last: PoseEstimate
    private var calibrator: StepLengthCalibrator? = null

    /** k measured on the legs out of START, once known (#12); null before that or if skipped. */
    var calibratedStepLengthPx: Double? = null
        private set

    /** Timestamp of the step that recalibrated the cloud, for the session log; null if none. */
    var calibratedAtNanos: Long? = null
        private set

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
        calibratedStepLengthPx = null
        calibratedAtNanos = null
        calibrator = if (config.autoCalibrateStepLength) {
            StepLengthCalibrator(graph, startNodeId, initialHeadingRad, stepLengthPx, config)
        } else {
            null
        }
        last = estimate(0L, initialHeadingRad)
    }

    override fun onMotion(event: MotionEvent): PoseEstimate {
        check(::graph.isInitialized) { "call start() first" }
        // Turn events add nothing yet: every step already carries its absolute heading.
        val step = event as? MotionEvent.Step
            ?: return last.copy(timestampNanos = event.timestampNanos)
        val heading = step.headingRad
        calibrator?.let { c ->
            c.onStep(heading)?.let { recalibrate(it, heading, step.timestampNanos) }
            if (c.finished) calibrator = null
        }
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

    /**
     * Re-draws every particle's k around the calibrated k, and walks a random
     * `calibrationReplaceFraction` of the cloud from the turn node along the exit corridor for
     * the steps since the turn. Kept particles keep their place, with their weight scaled by
     * `calibrationKeptWeight` against the re-placed ones, so they take over only if the map
     * later disagrees with the turn placement.
     */
    private fun recalibrate(
        calibration: StepLengthCalibrator.Calibration,
        heading: Double,
        timestampNanos: Long,
    ) {
        val k = calibration.stepLengthPx
        calibratedStepLengthPx = k
        calibratedAtNanos = timestampNanos
        for (p in particles) {
            val drawn = k * (1 + config.calibratedStepLengthSpread * gaussian())
            p.stepLengthPx = drawn.coerceAtLeast(0.5 * k)
        }
        normalise()
        // Kept particles become a minority hypothesis, not an equal one: on a straight corridor
        // nothing tells the two groups apart, and the old one is on the wrong k.
        val kept = ln(config.calibrationKeptWeight)
        for (p in particles) p.logWeight += kept
        val turn = calibration.turn
        val edge = graph.edges.first { it.id == turn.edgeId }
        val forward = turn.towardNodeId == edge.to
        // This step is walked by onMotion next, so place the steps before it.
        val past = (calibration.stepsPast - 1).coerceAtLeast(0)
        val count = (config.calibrationReplaceFraction * particles.size).toInt()
        for (p in particles.shuffled(random).take(count)) {
            p.edge = edge
            p.forward = forward
            p.offsetPx = if (forward) 0.0 else edge.lengthPx
            p.logWeight = 0.0
            advance(p, past * p.stepLengthPx * (1 + config.stepLengthNoise * gaussian()), heading)
        }
    }

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
