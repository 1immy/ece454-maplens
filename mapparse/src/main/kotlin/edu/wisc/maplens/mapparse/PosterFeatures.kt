package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.edge
import edu.wisc.maplens.model.nearestEdge
import java.util.Locale
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** A printed feature found on the poster: an exit sign, the elevator or an AED. */
internal data class PosterFeature(val type: NodeType, val at: PixelPoint)

/** Tolerances for feature detection, in pixels of the 2000 px straightened poster. */
internal object FeatureParams {
    /** A feature farther than this from every corridor is in the legend or off the plan. */
    const val CORRIDOR_REACH_PX = 120.0

    /**
     * Elevators get further: they never appear in the legend, and a lift lobby can sit off an
     * unpainted corridor the trace misses (EH1 east lift: 149 px from the nearest trace).
     */
    const val LIFT_REACH_PX = 180.0

    /** Signs of one kind closer than this are one feature (EH3's two lift doors: 65 px). */
    const val SAME_FEATURE_PX = 80.0

    /** An existing node this close takes the feature instead of a new one. */
    const val NODE_SNAP_PX = 12.0

    /** A feature node beside a door or junction sits this far off the corridor, toward it. */
    const val STUB_PX = 30.0

    // AED: a red heart filling a blue square. 35–75 px; blue fills ≥ 15% of the padded box.
    const val HEART_MIN_PX = 30
    const val HEART_MAX_PX = 80
    const val HEART_PAD_PX = 8
    const val HEART_MIN_BLUE = 0.15

    // Elevator: a light-blue box crossed by a white X, ~57 × 42 px, in pairs on EH3.
    const val LIFT_MIN_W = 35
    const val LIFT_MAX_W = 95
    const val LIFT_MIN_H = 28
    const val LIFT_MAX_H = 75
    const val LIFT_MIN_FILL = 0.5
    const val LIFT_MIN_WHITE = 0.06
    const val LIFT_MAX_RED = 0.03
}

/** Exit signs from the OCR words: "EXIT", and reads with an arrow or "To" run into it. */
internal fun exitSigns(readings: List<PosterReading>): List<PosterFeature> =
    readings.filter { isExitText(it.text) }.map { PosterFeature(NodeType.EXIT, it.center) }

/** AED icons: red hearts on blue squares (the legend's copy is dropped later, off the plan). */
internal fun aedIcons(hsv: Mat): List<PosterFeature> = Mats().use { mats ->
    val red = mats.mat()
    val redHigh = mats.mat()
    Core.inRange(hsv, Scalar(0.0, 110.0, 70.0), Scalar(12.0, 255.0, 255.0), red)
    Core.inRange(hsv, Scalar(166.0, 110.0, 70.0), Scalar(180.0, 255.0, 255.0), redHigh)
    Core.bitwise_or(red, redHigh, red)
    val blue = mats.mat()
    Core.inRange(hsv, Scalar(98.0, 60.0, 40.0), Scalar(130.0, 255.0, 255.0), blue)
    components(red, mats).map { it.first }.mapNotNull { box ->
        val p = FeatureParams
        if (box.width !in p.HEART_MIN_PX..p.HEART_MAX_PX ||
            box.height !in p.HEART_MIN_PX..p.HEART_MAX_PX
        ) {
            return@mapNotNull null
        }
        val padded = padded(box, p.HEART_PAD_PX, hsv)
        val share = Core.countNonZero(mats.own(blue.submat(padded))).toDouble() / padded.area()
        if (share < p.HEART_MIN_BLUE) return@mapNotNull null
        PosterFeature(NodeType.AED, center(box))
    }
}

/** Elevator symbols: solid light-blue boxes with a white X, merged when side by side. */
internal fun elevatorIcons(hsv: Mat): List<PosterFeature> = Mats().use { mats ->
    val blue = mats.mat()
    Core.inRange(hsv, Scalar(95.0, 90.0, 70.0), Scalar(130.0, 255.0, 255.0), blue)
    val white = mats.mat()
    Core.inRange(hsv, Scalar(0.0, 0.0, 160.0), Scalar(180.0, 60.0, 255.0), white)
    val red = mats.mat()
    Core.inRange(hsv, Scalar(0.0, 110.0, 70.0), Scalar(12.0, 255.0, 255.0), red)
    val p = FeatureParams
    val boxes = components(blue, mats).filter { (box, area) ->
        box.width in p.LIFT_MIN_W..p.LIFT_MAX_W && box.height in p.LIFT_MIN_H..p.LIFT_MAX_H &&
            area.toDouble() / box.area() >= p.LIFT_MIN_FILL &&
            share(white, box, mats) >= p.LIFT_MIN_WHITE &&
            share(red, box, mats) <= p.LIFT_MAX_RED
    }.map { center(it.first) }
    boxes.map { PosterFeature(NodeType.ELEVATOR, it) }
}

private fun share(mask: Mat, box: Rect, mats: Mats): Double =
    Core.countNonZero(mats.own(mask.submat(box))).toDouble() / box.area()

private fun padded(box: Rect, pad: Int, image: Mat): Rect {
    val x0 = (box.x - pad).coerceAtLeast(0)
    val y0 = (box.y - pad).coerceAtLeast(0)
    val x1 = (box.x + box.width + pad).coerceAtMost(image.cols())
    val y1 = (box.y + box.height + pad).coerceAtMost(image.rows())
    return Rect(x0, y0, x1 - x0, y1 - y0)
}

private fun center(box: Rect) =
    PixelPoint(box.x + box.width / 2.0, box.y + box.height / 2.0)

/** Bounding box and pixel count of every 8-connected blob in [mask]. */
private fun components(mask: Mat, mats: Mats): List<Pair<Rect, Int>> {
    val labels = mats.mat()
    val stats = mats.mat()
    val centroids = mats.mat()
    val count = Imgproc.connectedComponentsWithStats(
        mask,
        labels,
        stats,
        centroids,
        8,
        CvType.CV_32S
    )
    return (1 until count).map { i ->
        val row = IntArray(5)
        stats.get(i, 0, row)
        Rect(row[0], row[1], row[2], row[3]) to row[4]
    }
}

/**
 * Adds [features] to the graph as EXIT, ELEVATOR and AED nodes so they can be searched and
 * routed to ("nearest exit"). Features of one kind within [FeatureParams.SAME_FEATURE_PX] are
 * one; a feature farther than [FeatureParams.CORRIDOR_REACH_PX] from every corridor is skipped,
 * which drops the legend's AED icon and its "AED Located" list beside the plan.
 *
 * Each feature takes the closest corridor point: a corridor end there becomes the feature, a
 * plain stretch is split, and a door, junction or START keeps its role while the feature hangs
 * off it on a short stub toward the sign. Pure graph code, no Android.
 */
internal fun FloorGraph.attachFeatures(features: List<PosterFeature>): FloorGraph {
    var graph = this
    val merged = mutableListOf<PosterFeature>()
    for (feature in features) {
        val same = merged.indexOfFirst {
            it.type == feature.type && it.at.distanceTo(feature.at) <= FeatureParams.SAME_FEATURE_PX
        }
        if (same < 0) {
            merged += feature
        } else {
            val other = merged[same].at
            merged[same] = feature.copy(
                at = PixelPoint((other.x + feature.at.x) / 2, (other.y + feature.at.y) / 2),
            )
        }
    }
    for (feature in merged) graph = graph.attachFeature(feature)
    return graph
}

private fun FloorGraph.attachFeature(feature: PosterFeature): FloorGraph {
    val hit = nearestEdge(feature.at) ?: return this
    val maxGap = if (feature.type == NodeType.ELEVATOR) {
        FeatureParams.LIFT_REACH_PX
    } else {
        FeatureParams.CORRIDOR_REACH_PX
    }
    if (hit.distancePx > maxGap) return this
    val edge = edge(hit.edgeId)
    val near = endpointNear(edge, hit.point, FeatureParams.NODE_SNAP_PX)
    val degree = near?.let { node -> edges.count { it.from == node.id || it.to == node.id } }
    if (near != null && near.type == feature.type) return this
    if (near != null && degree == 1 && near.type == NodeType.DEAD_END &&
        landmarks.none { it.nodeId == near.id }
    ) {
        return copy(nodes = nodes + (near.id to near.copy(type = feature.type)))
    }
    val prefix = feature.type.name.lowercase(Locale.ROOT).take(3)
    if (near == null) {
        val node = Node(nextNodeId(prefix), hit.point, feature.type)
        return split(edge, hit, node)
    }
    // A door, junction or START already sits here: hang the feature beside it.
    val dx = feature.at.x - near.position.x
    val dy = feature.at.y - near.position.y
    val length = kotlin.math.hypot(dx, dy)
    val reach = minOf(length, FeatureParams.STUB_PX)
    if (reach < 1.0) return this
    val at =
        PixelPoint(near.position.x + dx / length * reach, near.position.y + dy / length * reach)
    val node = Node(nextNodeId(prefix), at, feature.type)
    val stubId = generateSequence(1) { it + 1 }.map { "stub$it" }.first { id ->
        edges.none { it.id == id }
    }
    val stub = Edge(stubId, near.id, node.id, listOf(near.position, at), reach)
    return copy(nodes = nodes + (node.id to node), edges = edges + stub)
}
