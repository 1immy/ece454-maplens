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

    /** The point [s] px along the trail (clamped to its ends). */
    fun pointAt(s: Double): PixelPoint {
        if (s <= 0.0 || points.size == 1) return points.first()
        if (s >= length) return points.last()
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
    }
}
