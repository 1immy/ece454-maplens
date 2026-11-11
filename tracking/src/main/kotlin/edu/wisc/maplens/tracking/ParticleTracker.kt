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
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
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
 * **Re-anchoring (#19).** A room sign the camera read ([onSighting]) is matched to its door.
 * It is accepted only if particles holding `signGateMinWeight` of the weight already lie
 * within `max(3σ, scanAcceptMinMeters)` of that door, so a misread or a sign seen down a long
 * corridor can't teleport the user. The cloud is then re-weighted around where the camera
 * puts the walker (the door, pulled back along the camera's bearing by the distance read off
 * the characters), `signReinjectFraction` of it is re-placed there, and it is resampled.
 *
 * **Step length from landmarks.** Every accepted sign and every confirmed turn at a junction
 * is an anchor. Between two anchors the map gives the distance and the step detector the
 * steps, so k is refined (`k ← landmarkBlendOld·k + (1 − landmarkBlendOld)·k_obs`) and every
 * particle's k is re-drawn around it. Without GPS or Wi-Fi this is what keeps drift down on
 * long walks: each turn and each sign re-measures the stride.
 *
 * Not thread safe: one navigation worker owns it. Pass a seeded [random] for reproducible
 * tests.
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

    /** What happened to the last sign offered to [onSighting]; null before any. */
    var lastSign: SignResult? = null
        private set

    /** Steps taken since [start]. */
    private var steps = 0

    /** Last landmark (sign door or turn junction) and the step it was passed at. */
    private var anchorNodeId: String? = null
    private var anchorStep = 0

    /** Turn detection for landmark anchors: the current straight leg's direction. */
    private var legX = 0.0
    private var legY = 0.0
    private var offLegSteps = 0

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
        lastSign = null
        steps = 0
        // START is the first landmark: the first sign or turn can already measure k.
        anchorNodeId = startNodeId
        anchorStep = 0
        legX = cos(initialHeadingRad)
        legY = sin(initialHeadingRad)
        offLegSteps = 0
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
        steps++
        calibrator?.let { c ->
            c.onStep(heading)?.let { recalibrate(it, heading, step.timestampNanos) }
            if (c.finished) calibrator = null
        }
        for (p in particles) {
            // A step across the corridor means the particle overshot a corner: put it back on
            // the branch the user actually took before walking the step.
            recoverCorner(p, heading)
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
        trackLeg(heading, step.timestampNanos)
        return last
    }

    /** A sign with text only: matched to its door, no bearing or distance. */
    override fun onSign(observation: SignObservation): Boolean =
        onSighting(SignSighting(observation)).accepted

    /**
     * Re-anchors the cloud on a sign the camera read (see the class notes). Never a silent
     * jump: the caller shows the [SignResult] either way, and a rejected sign changes nothing.
     */
    fun onSighting(sighting: SignSighting): SignResult {
        check(::graph.isInitialized) { "call start() first" }
        val k = last.stepLengthPx
        val pxPerMeter = k / config.nominalStrideMeters
        val spread = last.spreadPx
        val doors = sighting.observation.matchedNodeId?.let { listOf(it) }
            ?: SignText.doorsFor(sighting.observation.text, graph)?.second
        if (doors.isNullOrEmpty()) {
            return SignResult(false, SignOutcome.UNKNOWN_ROOM, null, 0.0, spread, spread)
                .also { lastSign = it }
        }
        // The gate: how much of the cloud is already near each candidate door.
        val gate = maxOf(
            config.scanAcceptSigmaMultiplier * spread,
            config.scanAcceptMinMeters * pxPerMeter,
        )
        val w = weights()
        val points = particles.map { graph.pointOnEdge(it.edge.id, it.offsetPx) }
        val (door, support) = doors.map { id ->
            val at = graph.nodes.getValue(id).position
            id to particles.indices.sumOf { if (points[it].distanceTo(at) <= gate) w[it] else 0.0 }
        }.maxBy { it.second }
        if (support < config.signGateMinWeight) {
            return SignResult(false, SignOutcome.TOO_FAR, door, support, spread, spread)
                .also { lastSign = it }
        }

        // Where the camera puts the walker: back from the door along the line of sight.
        val doorAt = graph.nodes.getValue(door).position
        val bearing = sighting.bearingRad
        val distance = sighting.distancePx
            ?.takeIf { it.isFinite() && it > 0 }
            ?.coerceAtMost(config.signMaxReadMeters * pxPerMeter)
        val seen = if (bearing != null && distance != null) {
            PixelPoint(doorAt.x - distance * cos(bearing), doorAt.y - distance * sin(bearing))
        } else {
            doorAt
        }
        val target = graph.nearestEdge(seen)?.point ?: doorAt
        val sigma = maxOf(
            config.signAnchorSigmaMeters * pxPerMeter,
            config.signDistanceSigmaShare * (distance ?: 0.0),
        )
        for (i in particles.indices) {
            val d = points[i].distanceTo(target)
            particles[i].logWeight -= d * d / (2 * sigma * sigma)
        }
        normalise()
        // Re-place the weakest share of the cloud around the anchor, on the corridors there.
        val count = (config.signReinjectFraction * particles.size).toInt()
        val edgesById = graph.edges.associateBy { it.id }
        for (p in particles.sortedBy { it.logWeight }.take(count)) {
            val near = PixelPoint(
                target.x + 0.5 * sigma * gaussian(),
                target.y + 0.5 * sigma * gaussian(),
            )
            val on = graph.nearestEdge(near) ?: continue
            p.edge = edgesById.getValue(on.edgeId)
            p.offsetPx = on.offsetPx
            p.logWeight = 0.0
        }
        normalise()
        resample()
        val refined = anchorAt(door, last.timestampNanos)
        last = estimate(last.timestampNanos, last.headingRad)
        return SignResult(true, SignOutcome.ACCEPTED, door, support, spread, last.spreadPx, refined)
            .also { lastSign = it }
    }

    /**
     * Turn anchors: a heading `turnAnchorMinDeg` off the current leg for
     * `turnAnchorConfirmSteps` steps is a turn. If the cloud is confident and the estimate is
     * just past a junction, that junction is a landmark ([anchorAt]).
     */
    private fun trackLeg(heading: Double, timestampNanos: Long) {
        val leg = atan2(legY, legX)
        if (bearingDifference(heading, leg) <= Math.toRadians(config.turnAnchorMinDeg)) {
            offLegSteps = 0
            // Follow slow drift along the leg; a turn is a sudden change.
            legX = 0.8 * legX + 0.2 * cos(heading)
            legY = 0.8 * legY + 0.2 * sin(heading)
            return
        }
        offLegSteps++
        if (offLegSteps < config.turnAnchorConfirmSteps) return
        offLegSteps = 0
        legX = cos(heading)
        legY = sin(heading)
        // Only once the START calibration is done, and only from a confident estimate.
        if (calibrator != null || last.topClusterWeight < config.minTopClusterWeight) return
        val edge = last.edgeId?.let { id -> graph.edges.firstOrNull { it.id == id } } ?: return
        val k = last.stepLengthPx
        // The junction behind the walker on the corridor they turned into.
        val ahead = graph.bearingOnEdge(edge.id, 0.0, towardTo = true)
        val facingTo = bearingDifference(heading, ahead) <= Math.PI / 2
        val behindNode = if (facingTo) edge.from else edge.to
        val node = graph.nodes.getValue(behindNode)
        val reach = (config.turnAnchorConfirmSteps + config.turnAnchorNodeSteps) * k
        if (node.position.distanceTo(last.position) > reach) return
        if ((adjacency[behindNode]?.size ?: 0) < 2) return
        // The turn began turnAnchorConfirmSteps steps ago, at the junction.
        anchorAt(behindNode, timestampNanos, steps - config.turnAnchorConfirmSteps)
    }

    /**
     * Makes [nodeId] the latest landmark, passed at step [atStep]. With an earlier landmark at
     * least `legMinSteps` back, refines k from the map distance between them over the steps
     * taken, if that is within `legMaxChange` of the current k. Returns the new k, if any.
     */
    private fun anchorAt(nodeId: String, timestampNanos: Long, atStep: Int = steps): Double? {
        val previous = anchorNodeId
        val walked = atStep - anchorStep
        anchorNodeId = nodeId
        anchorStep = atStep
        if (!config.landmarkStepLength || previous == null || previous == nodeId) return null
        if (walked < config.legMinSteps) return null
        val distance = graphDistance(previous, nodeId) ?: return null
        val k = last.stepLengthPx
        val observed = distance / walked
        if (abs(observed / k - 1) > config.legMaxChange) return null
        val blended = config.landmarkBlendOld * k + (1 - config.landmarkBlendOld) * observed
        for (p in particles) {
            val drawn = blended * (1 + config.calibratedStepLengthSpread * gaussian())
            p.stepLengthPx = drawn.coerceAtLeast(0.5 * blended)
        }
        calibratedStepLengthPx = blended
        calibratedAtNanos = timestampNanos
        return blended
    }

    /** Shortest corridor distance between two nodes (Dijkstra), or null if not connected. */
    private fun graphDistance(from: String, to: String): Double? {
        val best = hashMapOf(from to 0.0)
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        queue += from to 0.0
        while (queue.isNotEmpty()) {
            val (node, d) = queue.poll()
            if (node == to) return d
            if (d > best.getValue(node)) continue
            for ((edge, next) in adjacency[node].orEmpty()) {
                val nd = d + edge.lengthPx
                if (nd < (best[next] ?: Double.MAX_VALUE)) {
                    best[next] = nd
                    queue += next to nd
                }
            }
        }
        return null
    }

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
        // The calibration turn is a landmark too, passed stepsPast steps ago.
        anchorNodeId = if (forward) edge.from else edge.to
        anchorStep = steps - calibration.stepsPast
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

    /**
     * Corner recovery: when [heading] runs across [p]'s corridor (more than
     * `cornerMisfitDeg` off both ways along it), the particle most likely went a little past a
     * junction the user turned at, since step counts and k are never exact. If a node within
     * `cornerBacktrackSteps` steps either way has an exit within `cornerExitMaxDeg` of the
     * heading, the particle moves back to that node and onto that exit. Without this, every
     * particle just past a junction keeps walking straight on, and the estimate later jumps.
     */
    private fun recoverCorner(p: Particle, heading: Double) {
        val along = graph.bearingOnEdge(p.edge.id, p.offsetPx, towardTo = true)
        val misfit = minOf(
            bearingDifference(heading, along),
            bearingDifference(heading, along + Math.PI),
        )
        if (misfit <= Math.toRadians(config.cornerMisfitDeg)) return
        val reach = config.cornerBacktrackSteps * p.stepLengthPx
        var best: Triple<Edge, String, Double>? = null
        for ((node, distance) in listOf(
            p.edge.from to p.offsetPx,
            p.edge.to to p.edge.lengthPx - p.offsetPx
        )) {
            if (distance > reach) continue
            for ((exit, _) in adjacency[node].orEmpty()) {
                if (exit.id == p.edge.id) continue
                val bearing = if (exit.from == node) {
                    graph.bearingOnEdge(exit.id, 0.0, towardTo = true)
                } else {
                    graph.bearingOnEdge(exit.id, exit.lengthPx, towardTo = false)
                }
                val error = bearingDifference(heading, bearing)
                if (error <= Math.toRadians(config.cornerExitMaxDeg) &&
                    (best == null || error < best.third)
                ) {
                    best = Triple(exit, node, error)
                }
            }
        }
        val (exit, node, _) = best ?: return
        p.edge = exit
        p.forward = exit.from == node
        p.offsetPx = if (p.forward) 0.0 else exit.lengthPx
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
