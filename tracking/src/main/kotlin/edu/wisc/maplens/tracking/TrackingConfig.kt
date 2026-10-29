package edu.wisc.maplens.tracking

/**
 * Every tunable threshold in one place. Defaults are the proposal values;
 * pilot tuning changes them here, nowhere else.
 */
data class TrackingConfig(
    /** Requested 50 Hz for walking traces; Android may deliver a different rate. */
    val recordingPeriodUs: Int = 20_000,
    /** Live diagnostics refresh independently of the sensor sampling rate. */
    val recordingUiPeriodMs: Long = 250,
    val particleCount: Int = 300,
    /** Per-particle step-length noise, as a fraction of k. */
    val stepLengthNoise: Double = 0.10,
    /** LOW if particle spread exceeds this (about one door gap). */
    val maxSpreadMeters: Double = 3.0,
    /** LOW if the top cluster holds less than this share of weight. */
    val minTopClusterWeight: Double = 0.60,
    /** LOW if heading is this far off every corridor for [headingWindowSteps]. */
    val maxHeadingErrorDeg: Double = 30.0,
    val headingWindowSteps: Int = 10,
    /** Minimum steps between scan prompts. */
    val scanCooldownSteps: Int = 20,
    /** Sign accepted only within max(sigmaMultiplier * sigma, minMeters) of the cloud. */
    val scanAcceptSigmaMultiplier: Double = 3.0,
    val scanAcceptMinMeters: Double = 5.0,
    /** Weight kept on the old k when refining at a landmark. */
    val landmarkBlendOld: Double = 0.7,
    /** Display-only stride used to turn steps into meters. */
    val nominalStrideMeters: Double = 0.7,
)
