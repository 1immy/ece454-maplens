package edu.wisc.maplens.mapparse

import android.graphics.Bitmap
import edu.wisc.maplens.model.FloorGraph

/** Result of parsing a wall-map photo; always goes through "Check the map" before use. */
data class ParseResult(
    /** Unverified graph; the editor sets verified = true once validate() is clean. */
    val graph: FloorGraph,
    /** Homography-rectified image the graph's pixel coordinates refer to. */
    val rectified: Bitmap,
    val warnings: List<String>,
)

/**
 * Photo -> rectify -> mask corridors -> skeletonize -> graph, plus OCR labels and
 * the "You Are Here" marker. Owned by Area A.
 */
interface MapParser {
    fun parse(photo: Bitmap): ParseResult
}
