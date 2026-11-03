package edu.wisc.maplens

/**
 * Fixed values used until the app runs its own calibration walk (#12).
 * Not tracking thresholds: those live in `TrackingConfig`.
 */
object Demo {
    /**
     * Scale k, px/step on the EH3 elevator poster: route px over detector steps from Johnny's
     * walks 01 and 02 (#48, #50). One walker; other people's k will differ.
     */
    const val STEP_LENGTH_PX = 17.3

    /** Fake walker cadence, about 1.7 steps/s. */
    const val STEP_INTERVAL_MS = 600L
}
