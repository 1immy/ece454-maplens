package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.PixelPoint

/** How sure a room's door is. Stored in the door node's id prefix (see [doorIdPrefix]). */
internal enum class DoorKind {
    /** A doorway in the room's wall, owned by the room and opening onto a reachable corridor. */
    VERIFIED,

    /** No clear doorway: the corridor point nearest the room's corridor-facing wall. */
    APPROXIMATE,

    /** The room can't be closed off or reached cleanly: the old cheapest path, for review. */
    REVIEW,
}

/**
 * Node id prefix per kind, so the kind survives saving without a schema change: the app
 * reads it back (approximate ends say "near the room", review doors are flagged).
 */
internal fun doorIdPrefix(kind: DoorKind) = when (kind) {
    DoorKind.VERIFIED -> DoorIds.VERIFIED
    DoorKind.APPROXIMATE -> DoorIds.APPROXIMATE
    DoorKind.REVIEW -> DoorIds.REVIEW
}

/**
 * Door node id prefixes the fast parser writes, so saved maps keep how sure each door is
 * without a FloorGraph schema change. The app reads them back.
 */
object DoorIds {
    /** A doorway found in the room's wall. */
    const val VERIFIED = "ocrD"

    /** No clear doorway: the corridor point beside the room. Arrival says "near the room". */
    const val APPROXIMATE = "aprD"

    /** Placed by the fallback search: the review asks for a look. */
    const val REVIEW = "chkD"

    fun isApproximate(nodeId: String) = nodeId.startsWith(APPROXIMATE)

    fun needsReview(nodeId: String) = nodeId.startsWith(REVIEW)
}

/** Where a room's door is and how to reach it from the corridor. */
internal data class DoorFinding(
    val kind: DoorKind,
    /** Middle of the doorway in the wall; null for an approximate end. */
    val doorway: PixelPoint?,
    /** First open floor outside the room, where the walker stands at the door. */
    val outside: PixelPoint,
    /** The traced corridor point the way out reaches. */
    val corridor: PixelPoint,
    /** Open-floor path from [outside] to [corridor], simplified; at least two points. */
    val link: List<PixelPoint>,
    /** Why this one: for logs and the review. */
    val reason: String,
)
