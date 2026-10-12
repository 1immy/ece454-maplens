package edu.wisc.maplens.tracking

/** Scale k (pixels per step) maths from the proposal, slide "Distance is measured in steps". */
object StepLength {
    /** Calibration walk: k = pixels walked / steps taken. */
    fun calibrate(pixels: Double, steps: Int): Double {
        require(steps > 0) { "steps must be positive" }
        return pixels / steps
    }

    /** Refine at a landmark: blend, don't replace. */
    fun refine(current: Double, observed: Double, keepWeight: Double): Double {
        require(keepWeight in 0.0..1.0) { "keepWeight must be in [0, 1]" }
        return keepWeight * current + (1 - keepWeight) * observed
    }

    /** Display-only conversion; routing stays in pixels. */
    fun pixelsToMeters(pixels: Double, k: Double, strideMeters: Double): Double =
        pixels / k * strideMeters
}
