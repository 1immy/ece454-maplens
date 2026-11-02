package edu.wisc.maplens

/**
 * Stand-ins used until calibration (#12) and the tracker (#13) feed the app.
 * Not tracking thresholds: nothing here is used once real sensors drive the session.
 */
object Demo {
    /** Scale k, px/step: the report's calibration example (400 px / 16 steps). */
    const val STEP_LENGTH_PX = 25.0

    /** Fake walker cadence, about 1.7 steps/s. */
    const val STEP_INTERVAL_MS = 600L
}
