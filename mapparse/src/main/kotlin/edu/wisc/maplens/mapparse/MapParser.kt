package edu.wisc.maplens.mapparse

import android.graphics.Bitmap
import android.graphics.Rect
import edu.wisc.maplens.model.FloorGraph

/** Result of parsing a wall-map photo; always goes through "Check the map" before use. */
data class ParseResult(
    /** Draft graph with an empty startNodeId if START needs manual correction. */
    val graph: FloorGraph,
    /** Homography-rectified image the graph's pixel coordinates refer to. */
    val rectified: Bitmap,
    val warnings: List<String>,
    val rooms: List<RoomPreview> = emptyList(),
)

/** Parser stages in the order they run. */
enum class ParseStage { STRAIGHTEN, CORRIDORS, FIND_TEXT, READ_ROOMS, RECHECK_SMALL, PLACE_ROOMS }

/** The current stage, with its count when it has one. Reported from the parsing thread. */
data class ParseProgress(val stage: ParseStage, val done: Int = 0, val total: Int = 0)

/** OCR box in rectified pixels and the proposed door it belongs to, if one was found. */
data class RoomPreview(
    val label: String,
    val bounds: Rect,
    val entranceNodeId: String?,
    val confidence: Float = 1f,
    /** A weak read kept for the user to check. It is not a landmark in the graph. */
    val possible: Boolean = false,
)

/**
 * Photo -> rectify -> mask corridors -> skeletonize -> graph, plus OCR labels and
 * the "You Are Here" marker. Owned by Area A.
 */
interface MapParser {
    fun parse(photo: Bitmap): ParseResult
}
