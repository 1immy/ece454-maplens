package edu.wisc.maplens.tracking

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Experimental step detector from accelerometer magnitude peaks, for phones without
 * `TYPE_STEP_DETECTOR`. Off by default: it overcounted in Pixel 7a walks, because hand
 * movement can look like walking.
 *
 * Using |a| rather than one axis makes it independent of how the phone is tilted. Each heel
 * strike adds a bump to |a| on top of gravity (~9.8 m/s²):
 * 1. `gravity` is a slow low-pass of |a| (time constant `peakGravitySeconds`) that tracks the
 *    baseline, including sensor bias.
 * 2. `signal` is a fast low-pass of |a| − gravity (`peakSmoothingSeconds`) that removes jitter
 *    but keeps the bump.
 * 3. A step is the sample where `signal` stops rising, provided it peaked at or above
 *    `peakThreshold`, it dipped below `peakRearmThreshold` since the last step (hysteresis, so
 *    one noisy bump can't count twice), and at least `peakMinIntervalNs` has passed.
 *
 * Both filters use `1 − exp(−dt/τ)`, so they behave the same at any sampling rate.
 */
class AccelerationStepDetector(private val config: TrackingConfig = TrackingConfig()) {
    private var lastNs = 0L
    private var gravity = 0.0
    private var signal = 0.0

    /** Whether `signal` rose on the previous sample; a peak is rising followed by falling. */
    private var rising = false

    /** True once the signal has dipped below the re-arm level since the last step. */
    private var armed = false
    private var lastStepNs = 0L

    fun reset() {
        lastNs = 0L
        lastStepNs = 0L
        gravity = 0.0
        signal = 0.0
        rising = false
        armed = false
    }

    /**
     * Feeds one accelerometer sample (m/s², including gravity). Returns the timestamp of the
     * detected peak, which is the previous sample, or null if this sample completes no step.
     */
    fun update(timestampNs: Long, values: FloatArray): Long? {
        if (timestampNs <= lastNs || values.size < 3 || values.any { !it.isFinite() }) {
            return null
        }
        val magnitude = sqrt(values.take(3).sumOf { it.toDouble() * it })
        // First sample, or a gap long enough that the filters are stale: restart from here
        // instead of treating the jump as a step.
        if (lastNs == 0L || timestampNs - lastNs > config.peakMaxGapNs) {
            gravity = magnitude
            signal = 0.0
            armed = false
            rising = false
            lastNs = timestampNs
            return null
        }
        val dt = (timestampNs - lastNs) / 1e9
        val previousNs = lastNs
        lastNs = timestampNs
        gravity += (1 - exp(-dt / config.peakGravitySeconds)) * (magnitude - gravity)
        val next = signal + (1 - exp(-dt / config.peakSmoothingSeconds)) *
            (magnitude - gravity - signal)
        if (next < config.peakRearmThreshold) armed = true
        // `signal` (the previous sample) is a local maximum: it rose into it and falls after it.
        val peak = armed && rising && next < signal && signal >= config.peakThreshold &&
            previousNs - lastStepNs >= config.peakMinIntervalNs
        rising = next > signal
        signal = next
        if (!peak) return null
        armed = false
        lastStepNs = previousNs
        return previousNs
    }
}
