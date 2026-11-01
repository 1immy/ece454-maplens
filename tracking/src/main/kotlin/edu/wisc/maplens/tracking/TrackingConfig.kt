package edu.wisc.maplens.tracking

/**
 * Every tunable threshold in one place. Defaults are the proposal values;
 * pilot tuning changes them here, nowhere else.
 */
data class TrackingConfig(
    /** Requested 50 Hz for walking traces; Android may deliver a different rate. */
    val recordingPeriodUs: Int = 20_000,
    /** Live diagnostics refresh independently of the sensor sampling rate. */
    val recordingUiPeriodMs: Long = 100,
    /** Heading low-pass time constant: steadies hand jitter without lagging a real turn. */
    val headingSmoothingSeconds: Double = 0.08,
    /**
     * Minimum length of the floor-projected facing vector (unitless, 0 to √2). Below it, or with
     * the screen facing down past this much, the heading is reported as unavailable.
     */
    val headingMinProjection: Double = 0.25,
    /** A heading older than this is too stale to align to or to attach to a step. */
    val headingFreshnessNs: Long = 500_000_000,
    /** How far back a delayed step-detector event can look up its footfall heading. */
    val headingHistoryNs: Long = 3_000_000_000,
    /** Accelerometer-peak steps (experimental): slow baseline that tracks gravity. */
    val peakGravitySeconds: Double = 0.8,
    /** Fast smoothing of |a| − gravity; short enough to keep each heel-strike bump. */
    val peakSmoothingSeconds: Double = 0.04,
    /** Peak height, m/s² above baseline, that counts as a step. */
    val peakThreshold: Double = 0.75,
    /** The signal must dip below this (m/s²) before the next peak can count (hysteresis). */
    val peakRearmThreshold: Double = -0.15,
    /** At most one step per 0.3 s, i.e. about 3.3 steps/s, above a brisk walk. */
    val peakMinIntervalNs: Long = 300_000_000,
    /** A sample gap longer than this restarts the peak filters instead of bridging it. */
    val peakMaxGapNs: Long = 500_000_000,
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
