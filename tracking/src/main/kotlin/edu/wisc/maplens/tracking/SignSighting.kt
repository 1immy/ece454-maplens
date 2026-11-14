package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.SignObservation
import kotlin.math.abs

/**
 * A room sign the camera read, with where it was (#19). [SignObservation] (core/model) holds
 * the text; this adds the geometry the camera gives for free:
 *
 * - [bearingRad]: map bearing from the walker to the sign (camera heading plus the text's
 *   column in the picture), or null if unknown.
 * - [distancePx]: rough distance to the sign in map pixels, from how tall its characters
 *   look ([SignText.distanceMeters]), or null if unknown.
 *
 * - [roomCenter]: where the room's number is printed on the map. It says which side of the
 *   corridor the room (and so the sign on its wall) is on, which turns the camera bearing
 *   into a heading fix.
 *
 * Without either, the walker is only known to be near the door.
 */
data class SignSighting(
    val observation: SignObservation,
    val bearingRad: Double? = null,
    val distancePx: Double? = null,
    val roomCenter: PixelPoint? = null,
)

/** What the tracker did with a sign, for the screen and the session log. */
data class SignResult(
    val accepted: Boolean,
    val reason: SignOutcome,
    /** The door the sign was matched to, if any. */
    val doorNodeId: String? = null,
    /** Share of the cloud's weight that was already near that door (the gate). */
    val support: Double = 0.0,
    /** Spread before and after, px; equal when rejected. */
    val spreadBeforePx: Double = 0.0,
    val spreadAfterPx: Double = 0.0,
    /** Step length after a refinement at this sign, or null if k was left alone. */
    val refinedStepLengthPx: Double? = null,
    /** True when the cloud was elsewhere and was moved to the sign ([TrackingConfig.signRelocates]). */
    val relocated: Boolean = false,
    /**
     * Correction to add to the heading offset θ0: the true map bearing of the sign from where
     * the walker stands, minus the bearing the camera measured. Null when it can't be told.
     */
    val headingFixRad: Double? = null,
)

enum class SignOutcome {
    /** Re-anchored on the door. */
    ACCEPTED,

    /** No door on the map carries this number (or more than one number fits a misread). */
    UNKNOWN_ROOM,

    /** The door is too far from every place the tracker thinks the walker could be. */
    TOO_FAR,
}

/**
 * Room-number text: normalizing OCR the way A5 asks, matching it to doors, and reading a
 * distance off the character height.
 */
object SignText {
    /** Room numbers like 3418 or 3539A, after [normalize]. */
    private val ROOM = Regex("""\d{3,4}[A-Z]?""")

    /**
     * Upper-case, no spaces or punctuation, and the usual OCR confusions in the digits fixed:
     * O→0, I/l→1, S→5 (a trailing letter suffix such as the A of 3539A is kept).
     */
    fun normalize(text: String): String {
        val compact = text.filter { it.isLetterOrDigit() }
        if (compact.isEmpty()) return ""
        val body = compact.dropLast(1).map(::digitLike).joinToString("")
        val last = compact.last()
        // A letter after four digits is a suffix (3539A); anywhere else it is a misread digit.
        val tail = if (compact.length >= 5) last.uppercaseChar() else digitLike(last)
        return (body + tail).uppercase()
    }

    private fun digitLike(c: Char): Char = when (c) {
        'O', 'o' -> '0'
        'I', 'l', 'i', '|' -> '1'
        'S', 's' -> '5'
        else -> c
    }

    /** True for text that reads like a room number once normalized. */
    fun isRoomNumber(text: String): Boolean = ROOM.matches(normalize(text))

    /**
     * Door nodes for [text]: every door whose label matches exactly, or, failing that, the
     * doors of the one label within a single character edit. Several labels one edit away is
     * ambiguous and gives none: a misread must never silently pick a room.
     */
    fun doorsFor(text: String, graph: FloorGraph): Pair<String, List<String>>? {
        val wanted = normalize(text)
        if (!ROOM.matches(wanted)) return null
        val byLabel = graph.landmarks
            .filter { graph.nodes[it.nodeId]?.type == NodeType.DOOR }
            .groupBy({ normalize(it.label) }, { it.nodeId })
        byLabel[wanted]?.let { return wanted to it.distinct() }
        val near = byLabel.keys.filter { editDistanceAtMostOne(it, wanted) }
        return near.singleOrNull()?.let { it to byLabel.getValue(it).distinct() }
    }

    /**
     * Distance to a sign whose characters are [textHeightMeters] tall and appear
     * [textHeightPx] tall in a picture with focal length [focalPx] (pinhole camera).
     */
    fun distanceMeters(textHeightPx: Double, focalPx: Double, textHeightMeters: Double): Double? =
        if (textHeightPx > 0 && focalPx > 0) focalPx * textHeightMeters / textHeightPx else null

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        if (a.length == b.length) return a.zip(b).count { (x, y) -> x != y } <= 1
        val (short, long) = if (a.length < b.length) a to b else b to a
        var i = 0
        var j = 0
        var skipped = false
        while (i < short.length && j < long.length) {
            if (short[i] == long[j]) {
                i++
                j++
            } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }
}
