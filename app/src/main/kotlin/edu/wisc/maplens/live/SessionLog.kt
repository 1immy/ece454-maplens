package edu.wisc.maplens.live

import edu.wisc.maplens.model.ConfidenceState
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.nearestEdge
import edu.wisc.maplens.tracking.SignResult
import java.io.Writer
import java.util.Locale

/**
 * Corridor id for every edge: edges joined at a node with exactly two edges (a door or a bend)
 * are one corridor; junctions, dead ends and other endpoints split them. "Correct corridor"
 * in the G2 gate is judged on these ids, since door nodes split one hallway into many edges.
 */
fun FloorGraph.corridorIds(): Map<String, String> {
    val parent = edges.associate { it.id to it.id }.toMutableMap()
    fun root(id: String): String {
        var r = id
        while (parent.getValue(r) != r) r = parent.getValue(r)
        return r
    }
    for ((_, links) in adjacency()) {
        if (links.size == 2) {
            val a = root(links[0].first.id)
            val b = root(links[1].first.id)
            if (a != b) parent[maxOf(a, b)] = minOf(a, b)
        }
    }
    return edges.associate { it.id to root(it.id) }
}

/** A tapped ground-truth position compared with the estimate at that moment. */
data class CheckpointResult(
    val label: String,
    val estimate: PoseEstimate,
    val truth: PixelPoint,
    val truthEdgeId: String?,
    val errorPx: Double,
    /** Display-only: px ÷ k × stride. */
    val errorMeters: Double,
    val sameCorridor: Boolean,
)

fun FloorGraph.checkpoint(
    label: String,
    estimate: PoseEstimate,
    truth: PixelPoint,
    strideMeters: Double,
    corridors: Map<String, String> = corridorIds(),
): CheckpointResult {
    val truthEdge = nearestEdge(truth)?.edgeId
    val errorPx = estimate.position.distanceTo(truth)
    val same = truthEdge != null && estimate.edgeId != null &&
        corridors[truthEdge] == corridors[estimate.edgeId]
    return CheckpointResult(
        label = label,
        estimate = estimate,
        truth = truth,
        truthEdgeId = truthEdge,
        errorPx = errorPx,
        errorMeters = errorPx / estimate.stepLengthPx * strideMeters,
        sameCorridor = same,
    )
}

/**
 * Per-session CSV for the G2 gate and the A–D trials (section 9): every pose, every checkpoint
 * and arrival. Not thread safe; the Activity writes it from one background executor.
 */
class SessionLog(private val out: Writer) {
    init {
        out.write("$HEADER\n")
        out.flush()
    }

    /** First row: what was started, in which mode, facing which bearing (θ0 anchor). */
    fun start(timestampNs: Long, mode: String, room: String, facingRad: Double, kPx: Double) =
        row(timestampNs, "start", "$mode:$room", kPx = kPx, extra = fmt(facingRad))

    fun pose(pose: PoseEstimate) =
        row(pose.timestampNanos, "pose", "", pose, kPx = pose.stepLengthPx)

    fun checkpoint(timestampNs: Long, c: CheckpointResult) = row(
        timestampNs = timestampNs,
        event = "checkpoint",
        label = c.label,
        estimate = c.estimate,
        truth = c.truth,
        truthEdge = c.truthEdgeId,
        errorPx = c.errorPx,
        errorMeters = c.errorMeters,
        same = c.sameCorridor,
        kPx = c.estimate.stepLengthPx,
    )

    /**
     * A detected footfall and the walking gate's [verdict] on it (WALKING moved the user),
     * with the phone's vertical bob (m/s² RMS) at the time, in the heading column.
     */
    fun candidate(timestampNs: Long, verdict: String, bob: Double?) =
        row(timestampNs, "candidate", verdict, extra = fmt(bob))

    /** The tracker re-measured k on the legs out of START (#12); [kPx] is the new k. */
    fun recalibrate(timestampNs: Long, kPx: Double) =
        row(timestampNs, "recalibrate", "", kPx = kPx)

    /**
     * Confidence changed (#18): level and failed checks in the label (`LOW:SPREAD|GROUP_SPLIT`),
     * the suggested recovery in the heading column.
     */
    fun confidence(state: ConfidenceState) = row(
        state.timestampNanos,
        "confidence",
        state.level.name + state.failedChecks.map { it.name }.sorted()
            .joinToString("|", prefix = ":").takeIf { state.failedChecks.isNotEmpty() }.orEmpty(),
        extra = state.suggestedRecovery?.name.orEmpty(),
    )

    /**
     * A new route from [pose] because of [reason] (`OFF_ROUTE`, `WRONG_WAY`, #80); the new
     * route's length in px is in the error_px column.
     */
    fun reroute(pose: PoseEstimate, reason: String, routeLengthPx: Double) = row(
        pose.timestampNanos,
        "reroute",
        reason,
        pose,
        errorPx = routeLengthPx,
        kPx = pose.stepLengthPx,
    )

    /**
     * A room sign offered to the tracker (#19): `3418:ACCEPTED` (or `TOO_FAR`, `UNKNOWN_ROOM`)
     * in the label, the pose after it if accepted, the spread after in error_px, and the new
     * k if the sign refined it.
     */
    fun sign(timestampNs: Long, label: String, result: SignResult, pose: PoseEstimate?) = row(
        timestampNs,
        "sign",
        // e.g. "3546:ACCEPTED:MOVED:FIX-31": the cloud was moved, θ0 turned by -31°.
        buildString {
            append("$label:${result.reason.name}")
            if (result.relocated) append(":MOVED")
            result.headingFixRad?.let {
                append(":FIX").append(String.format(Locale.US, "%+.0f", Math.toDegrees(it)))
            }
        },
        pose,
        errorPx = result.spreadAfterPx,
        kPx = result.refinedStepLengthPx,
    )

    fun arrive(pose: PoseEstimate) =
        row(pose.timestampNanos, "arrive", "", pose, kPx = pose.stepLengthPx)

    fun close() = out.close()

    private fun row(
        timestampNs: Long,
        event: String,
        label: String,
        estimate: PoseEstimate? = null,
        truth: PixelPoint? = null,
        truthEdge: String? = null,
        errorPx: Double? = null,
        errorMeters: Double? = null,
        same: Boolean? = null,
        kPx: Double? = null,
        extra: String = "",
    ) {
        val fields = listOf(
            timestampNs.toString(),
            event,
            label,
            fmt(estimate?.position?.x),
            fmt(estimate?.position?.y),
            estimate?.edgeId.orEmpty(),
            fmt(estimate?.headingRad).ifEmpty { extra },
            fmt(truth?.x),
            fmt(truth?.y),
            truthEdge.orEmpty(),
            fmt(errorPx),
            fmt(errorMeters),
            same?.toString().orEmpty(),
            fmt(kPx),
        )
        out.write(fields.joinToString(",") + "\n")
        out.flush()
    }

    private fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.3f", it) }.orEmpty()

    companion object {
        const val HEADER = "timestamp_ns,event,label,est_x,est_y,est_edge,heading_rad," +
            "true_x,true_y,true_edge,error_px,error_m,same_corridor,k_px"
    }
}
