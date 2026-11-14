package edu.wisc.maplens.nav

import edu.wisc.maplens.model.PixelPoint

/**
 * The path the user has walked so far, as the tracker reported it: one point per position
 * update, joined by straight lines. The live dot moves along this, so it follows the user
 * through turns, U-turns and detours alike, wherever they go relative to the route.
 */
class Trail(start: PixelPoint) {
    private val points = mutableListOf(start)
    private val lengths = mutableListOf(0.0)

    /** Total length walked, px. */
    val length: Double get() = lengths.last()

    /** Adds the tracker's latest position; returns the trail length up to it. */
    fun add(p: PixelPoint): Double {
        val step = points.last().distanceTo(p)
        if (step > MIN_STEP_PX) {
            points += p
            lengths += length + step
        }
        return length
    }

    /**
     * The point [s] px along the trail. Past its end the trail carries on in the direction of
     * its last stretch, up to [MAX_AHEAD_PX]: between footfalls the dot walks a little ahead of
     * the last fix (PuckMotion), and clamping it at the end froze the dot until the next step,
     * then jumped it ~30 px in one frame, so live walking looked frame-by-frame (Nov 13).
     */
    fun pointAt(s: Double): PixelPoint {
        if (s <= 0.0 || points.size == 1) return points.first()
        if (s >= length) {
            val end = points.last()
            val from = points[points.lastIndex - 1]
            val stretch = from.distanceTo(end)
            if (stretch == 0.0) return end
            val ahead = (s - length).coerceAtMost(MAX_AHEAD_PX)
            return PixelPoint(
                end.x + (end.x - from.x) / stretch * ahead,
                end.y + (end.y - from.y) / stretch * ahead,
            )
        }
        var lo = 0
        var hi = lengths.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (lengths[mid] <= s) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val t = (s - lengths[lo]) / (lengths[hi] - lengths[lo])
        return PixelPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    private companion object {
        const val MIN_STEP_PX = 0.5

        /** Furthest past the last fix the dot is drawn: about two steps. */
        const val MAX_AHEAD_PX = 40.0
    }
}
