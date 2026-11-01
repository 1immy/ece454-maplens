package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Something seen during a turn-on-the-spot scan. [relativeRad] is its direction in the
 * game-rotation-vector frame (arbitrary zero), so map bearing = relativeRad + offset.
 */
sealed interface OrientObservation {
    val relativeRad: Double

    /** A recognized room number; [label] is already OCR-normalized (A5). */
    data class Door(val label: String, override val relativeRad: Double) : OrientObservation

    /** A corridor's line direction from vanishing points: undirected, so modulo 180°. */
    data class CorridorAxis(override val relativeRad: Double) : OrientObservation

    /** Looking down an open corridor in this direction. */
    data class Opening(override val relativeRad: Double) : OrientObservation

    /** A wall fills the view in this direction. */
    data class Blocked(override val relativeRad: Double) : OrientObservation
}

enum class OrientStatus { CLEAR, AMBIGUOUS, NO_EVIDENCE }

/** [offsetRad] is θ0 in θmap = ψ + θ0, the same quantity `HeadingEstimator` aligns. */
data class OrientCandidate(val offsetRad: Double, val logLikelihood: Double)

data class OrientResult(
    val status: OrientStatus,
    /** Best first; peaks at least [TrackingConfig.orientPeakSeparationDeg] apart. */
    val candidates: List<OrientCandidate>,
    val usedObservations: Int,
) {
    val best: OrientCandidate? get() = candidates.firstOrNull()
}

/**
 * Finds the heading offset that best lines up scan observations with the corridors and
 * doors around the user's node (ADR 0004). Map bearings use atan2(dy, dx) in map pixels:
 * 0 is map right, +π/2 is map down. Pure Kotlin; no camera or Android types.
 */
class OrientationSolver(private val config: TrackingConfig = TrackingConfig()) {

    fun solve(
        graph: FloorGraph,
        nodeId: String,
        observations: List<OrientObservation>,
    ): OrientResult {
        val here = requireNotNull(graph.nodes[nodeId]) { "Unknown node $nodeId" }.position
        val corridors = graph.adjacency()[nodeId].orEmpty().map { (edge, other) ->
            val path = if (edge.from == nodeId) edge.polyline else edge.polyline.asReversed()
            val next = path.firstOrNull { it.distanceTo(here) > 1e-6 }
                ?: graph.nodes.getValue(other).position
            bearing(here, next)
        }
        val doors = graph.landmarks.groupBy({ it.label.uppercase() }) { graph.nodes[it.nodeId] }
        val terms = observations.mapNotNull { obs ->
            when (obs) {
                is OrientObservation.Door -> doors[obs.label.trim().uppercase()]
                    ?.mapNotNull { node -> node?.position?.takeIf { it.distanceTo(here) > 1e-6 } }
                    ?.map { bearing(here, it) }?.takeIf { it.isNotEmpty() }
                    ?.let { targets -> term(obs, config.orientDoorSigmaDeg) { error(it, targets) } }
                // Corridor evidence needs at least one corridor at this node.
                is OrientObservation.CorridorAxis -> if (corridors.isEmpty()) {
                    null
                } else {
                    term(obs, config.orientAxisSigmaDeg) { axisError(it, corridors) }
                }
                is OrientObservation.Opening -> if (corridors.isEmpty()) {
                    null
                } else {
                    term(obs, config.orientOpeningSigmaDeg) { error(it, corridors) }
                }
                is OrientObservation.Blocked -> if (corridors.isEmpty()) {
                    null
                } else {
                    term(obs, config.orientOpeningSigmaDeg, negative = true) {
                        error(it, corridors)
                    }
                }
            }
        }
        if (terms.isEmpty()) return OrientResult(OrientStatus.NO_EVIDENCE, emptyList(), 0)

        val scores = DoubleArray(BINS) { bin -> terms.sumOf { it(binRad(bin)) } }
        val separation = (config.orientPeakSeparationDeg / BIN_DEG).roundToInt()
        val floor = scores.min() + FLAT_TOLERANCE
        val peaks = mutableListOf<Int>()
        for (bin in scores.indices.sortedByDescending { scores[it] }) {
            // Where nothing matches the score is flat; those bins are not candidates.
            if (scores[bin] <= floor) break
            if (peaks.any { circularGap(it, bin) <= separation }) continue
            // A bin on the slope of an accepted peak is not a peak of its own.
            if ((-separation..separation).all { scores[bin] >= scores[(bin + it).mod(BINS)] }) {
                peaks += bin
            }
            if (peaks.size == MAX_CANDIDATES) break
        }
        if (peaks.isEmpty()) {
            return OrientResult(OrientStatus.NO_EVIDENCE, emptyList(), terms.size)
        }
        val candidates = peaks.map { bin ->
            OrientCandidate(refine(scores, bin, separation / 2), scores[bin])
        }
        val clear = candidates.size == 1 ||
            candidates[0].logLikelihood - candidates[1].logLikelihood >= config.orientClearMargin
        return OrientResult(
            if (clear) OrientStatus.CLEAR else OrientStatus.AMBIGUOUS,
            candidates,
            terms.size,
        )
    }

    /** Log of a Gaussian on the bearing error, mixed with a floor so outliers stay bounded. */
    private fun term(
        obs: OrientObservation,
        sigmaDeg: Double,
        negative: Boolean = false,
        errorAt: (Double) -> Double,
    ): (Double) -> Double {
        val sigma = Math.toRadians(sigmaDeg)
        val floor = config.orientOutlierFloor
        return { offset ->
            val e = errorAt(obs.relativeRad + offset)
            val match = exp(-e * e / (2 * sigma * sigma))
            ln(if (negative) 1 - (1 - floor) * match else floor + (1 - floor) * match)
        }
    }

    /** Weighted circular mean around a peak bin, for sub-degree output. */
    private fun refine(scores: DoubleArray, peak: Int, halfWidth: Int): Double {
        var s = 0.0
        var c = 0.0
        for (step in -halfWidth..halfWidth) {
            val bin = (peak + step).mod(BINS)
            val w = exp(scores[bin] - scores[peak])
            s += w * sin(binRad(bin))
            c += w * cos(binRad(bin))
        }
        return atan2(s, c)
    }

    private companion object {
        const val BIN_DEG = 1.0
        const val BINS = 360
        const val MAX_CANDIDATES = 4
        const val FLAT_TOLERANCE = 1e-9

        fun binRad(bin: Int) = wrap(Math.toRadians(bin * BIN_DEG))

        fun circularGap(a: Int, b: Int) = abs(a - b).let { minOf(it, BINS - it) }

        fun bearing(from: PixelPoint, to: PixelPoint) = atan2(to.y - from.y, to.x - from.x)

        fun wrap(angle: Double) = atan2(sin(angle), cos(angle))

        fun error(mapRad: Double, targets: List<Double>) =
            targets.minOf { abs(wrap(mapRad - it)) }

        /** Undirected: a corridor at 0° also matches an axis seen at 180°. */
        fun axisError(mapRad: Double, targets: List<Double>) =
            targets.minOf { abs(wrap(2 * (mapRad - it))) / 2 }
    }
}
