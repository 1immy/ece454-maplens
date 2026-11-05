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
    /** Spread of each particle's own k around the calibrated k at start, as a fraction. */
    val initialStepLengthSpread: Double = 0.15,
    /** Learn k on the legs out of START ([StepLengthCalibrator], #12) and re-draw particle k. */
    val autoCalibrateStepLength: Boolean = true,
    /** Calibrate at the first turn at least this many steps from START; ±1 step is then ≤ 7%. */
    val calibrationMinSteps: Int = 15,
    /** A step this far off the leg's corridor may be a turn (half of a right angle). */
    val calibrationTurnDeg: Double = 45.0,
    /** Consecutive off-corridor steps that confirm a turn, so one wobble isn't one. */
    val calibrationTurnConfirmSteps: Int = 2,
    /**
     * Largest change from the starting k accepted, as a fraction; also how closely a turn node
     * must match the step count at the starting k when the corridor has several...
     */
    val calibrationMaxChange: Double = 0.5,
    /** ...or within this many steps, whichever is larger (short legs). */
    val calibrationStepSlack: Double = 3.0,
    /** Spread of each particle's k around the calibrated k once it is known, as a fraction. */
    val calibratedStepLengthSpread: Double = 0.10,
    /**
     * Share of the cloud re-placed just past the calibration turn; the rest keeps its place and
     * weight, so a turn put on the wrong branch is not a silent snap.
     */
    val calibrationReplaceFraction: Double = 0.5,
    /** Weight of a kept particle against a re-placed one right after calibration. */
    val calibrationKeptWeight: Double = 0.1,
    /** σθ in the heading weight exp(−(θ − β)² / 2σθ²), and for branch choice at junctions. */
    val headingSigmaDeg: Double = 20.0,
    /** Resample when the effective particle count drops below this fraction of N. */
    val resampleFraction: Double = 0.5,
    /** Weight multiplier for a particle that tries to walk past a dead end ("~0"). */
    val deadEndWeight: Double = 1e-3,
    /** Particles within this distance are one cluster; the estimate is the heaviest cluster. */
    val clusterRadiusMeters: Double = 2.0,
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
    /** Scan-to-orient (ADR 0006): bearing noise per observation kind. */
    val orientDoorSigmaDeg: Double = 10.0,
    /** Room signs sit this far off the corridor centre, as a share of typical door spacing. */
    val orientDoorSideFraction: Double = 0.3,
    val orientAxisSigmaDeg: Double = 5.0,
    val orientOpeningSigmaDeg: Double = 20.0,
    /** Likelihood floor so one misread or misdetection can't veto the true offset. */
    val orientOutlierFloor: Double = 0.05,
    /** Candidate offsets closer than this are one peak. */
    val orientPeakSeparationDeg: Double = 20.0,
    /** Auto-apply only if the best peak beats the runner-up by this log-likelihood (~10x). */
    val orientClearMargin: Double = 2.3,
    /** A photo reading this many room numbers is a posted map; facing it means map up. */
    val orientPosterMinLabels: Int = 3,
    val orientPosterSigmaDeg: Double = 10.0,
    /** Guided scan: photos per turn, evenly spaced, and the gates each photo must pass. */
    val orientScanPhotos: Int = 8,
    val orientCaptureYawToleranceDeg: Double = 4.0,
    val orientCaptureMaxPitchDeg: Double = 20.0,
    val orientCaptureMaxRollDeg: Double = 8.0,
    /** "Steady": yaw moved less than this over [orientCaptureSteadyMs]. */
    val orientCaptureSteadyDeg: Double = 1.5,
    val orientCaptureSteadyMs: Long = 400,
    /** Vanishing points: edges weaker than this share of the strongest don't vote. */
    val orientVpEdgeFraction: Double = 0.15,
    /** Edges this close to vertical (door frames) or to the horizon carry no depth. */
    val orientVpSkipVerticalDeg: Double = 15.0,
    val orientVpSkipHorizontalDeg: Double = 4.0,
    val orientVpPeakHalfWidthDeg: Int = 3,
    /** Neighbourhood radius (px) for edge direction, and how line-like (0–1) a voter must be. */
    val orientVpTensorRadius: Int = 2,
    val orientVpMinCoherence: Double = 0.7,
    /** A peak needs this share of all votes to count as a corridor direction. */
    val orientVpMinStrength: Double = 0.2,
    /** "Looking down the corridor" only if the point is inside this share of the view. */
    val orientVpInViewFraction: Double = 0.8,
)
