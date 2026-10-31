package edu.wisc.maplens.tracking

import kotlin.math.exp
import kotlin.math.sqrt

/** Experimental magnitude peaks: tilt independent, but hand shakes can resemble walking. */
class AccelerationStepDetector(private val config: TrackingConfig = TrackingConfig()) {
    private var lastNs = 0L
    private var gravity = 0.0
    private var signal = 0.0
    private var rising = false
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

    fun update(timestampNs: Long, values: FloatArray): Long? {
        if (timestampNs <= lastNs || values.size < 3 || values.any { !it.isFinite() }) {
            return null
        }
        val magnitude = sqrt(values.take(3).sumOf { it.toDouble() * it })
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
