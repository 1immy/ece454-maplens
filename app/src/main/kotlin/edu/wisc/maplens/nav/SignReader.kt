package edu.wisc.maplens.nav

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.SignObservation
import edu.wisc.maplens.model.wrapBearing
import edu.wisc.maplens.tracking.SignSighting
import edu.wisc.maplens.tracking.SignText
import kotlin.math.atan
import kotlin.math.tan

/** One line of text the OCR found, with its box in the upright picture (pixels). */
data class TextLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 0.9f,
)

/** A room number seen in one camera frame. */
data class SignDetection(
    /** The room as printed on the map (normalized), e.g. "3418". */
    val label: String,
    /** Its door nodes, or empty if no room on this map has that number. */
    val doors: List<String>,
    val line: TextLine,
    /** Map bearing from the walker to the sign. */
    val bearingRad: Double,
    /** Rough distance read off the character height, metres. */
    val distanceMeters: Double?,
)

/**
 * Turns OCR lines from one camera frame into room-number sightings (#19): which room, in which
 * direction on the map, and roughly how far. No GPS, no Wi-Fi, no depth sensor:
 *
 * - **Direction:** the camera's map heading (from the same game rotation vector the tracker
 *   uses) plus the text's angle off the picture's centre, from the lens' field of view.
 * - **Distance:** a pinhole camera sees characters `h` px tall at distance
 *   `f · H / h`, with `f` the focal length in px and `H` the printed character height
 *   ([textHeightMeters]). Rough (±30 %), which is why the tracker weighs it loosely.
 *
 * Pure Kotlin, so it unit-tests without a camera.
 */
class SignReader(
    private val graph: FloorGraph,
    private val textHeightMeters: Double,
) {
    /**
     * Room numbers in [lines] from a [width]-px-wide upright picture whose horizontal field of
     * view is [hfovRad], taken facing map bearing [headingRad].
     */
    fun read(
        lines: List<TextLine>,
        width: Int,
        hfovRad: Double,
        headingRad: Double
    ): List<SignDetection> {
        val focal = width / 2.0 / tan(hfovRad / 2)
        return lines.mapNotNull { line ->
            if (!SignText.isRoomNumber(line.text)) return@mapNotNull null
            val match = SignText.doorsFor(line.text, graph)
            val label = match?.first ?: SignText.normalize(line.text)
            val centre = (line.left + line.right) / 2.0
            val bearing = wrapBearing(headingRad + atan((centre - width / 2.0) / focal))
            val height = (line.bottom - line.top).toDouble()
            SignDetection(
                label = label,
                doors = match?.second.orEmpty(),
                line = line,
                bearingRad = bearing,
                // Tiny text: the box stops shrinking with it and pads its height, so a box much
                // narrower than the characters would be (about 0.55 of their height each) gives
                // no distance rather than a wrong one.
                distanceMeters = if (height < MIN_TEXT_PX ||
                    (line.right - line.left) / (height * label.length) < MIN_CHAR_ASPECT
                ) {
                    null
                } else {
                    SignText.distanceMeters(height, focal, textHeightMeters)
                },
            )
        }
    }
}

/**
 * Text boxes shorter than this (px) give no distance: ML Kit pads small boxes, so the height
 * stops tracking the text (on the phone, 6 px text came back as a 15 px box, reading 2.5 m
 * for 6 m). In a 1080 px wide frame this keeps distances to about 2.3 m; farther signs still
 * anchor, on the door alone.
 */
private const val MIN_TEXT_PX = 16.0

/** Width per character over box height below which the box height is padding, not text. */
private const val MIN_CHAR_ASPECT = 0.35

/**
 * Keeps a few frames of [SignDetection]s and decides when a room is read reliably enough to
 * use: seen in [needed] of the last [window] frames. Then [sighting] gives the median bearing
 * and distance over those frames, which smooths out one blurry read.
 */
class SignVoter(private val window: Int = 5, private val needed: Int = 3) {
    private val frames = ArrayDeque<List<SignDetection>>()

    fun add(detections: List<SignDetection>) {
        frames.addLast(detections.filter { it.doors.isNotEmpty() })
        while (frames.size > window) frames.removeFirst()
    }

    fun clear() = frames.clear()

    /** The room seen most often, if it reached [needed] frames, with its median geometry. */
    fun stable(): List<SignDetection>? {
        val byLabel = frames.flatMap { frame -> frame.distinctBy { it.label } }.groupBy { it.label }
        val best = byLabel.values.maxByOrNull { it.size } ?: return null
        return best.takeIf { it.size >= needed }
    }

    companion object {
        /** One sighting for the tracker from several frames' reads of the same room. */
        fun sighting(
            reads: List<SignDetection>,
            timestampNanos: Long,
            pxPerMeter: Double,
        ): SignSighting {
            val first = reads.first()
            val bearing = reads.map { it.bearingRad }.sorted()[reads.size / 2]
            val distances = reads.mapNotNull { it.distanceMeters }.sorted()
            val distance = distances.getOrNull(distances.size / 2)
            return SignSighting(
                observation = SignObservation(
                    timestampNanos = timestampNanos,
                    text = first.label,
                    ocrConfidence = reads.map { it.line.confidence }.average().toFloat(),
                    matchedNodeId = first.doors.singleOrNull(),
                ),
                bearingRad = bearing,
                distancePx = distance?.let { it * pxPerMeter },
            )
        }
    }
}
