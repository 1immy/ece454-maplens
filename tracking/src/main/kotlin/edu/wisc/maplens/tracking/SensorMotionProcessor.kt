package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.StepSource

/** Single sensor-worker owner. Delayed Android steps use heading at footfall, not delivery. */
class SensorMotionProcessor(private val config: TrackingConfig = TrackingConfig()) {
    private val estimator = HeadingEstimator(config)
    private val peaks = AccelerationStepDetector(config)
    private val history = ArrayDeque<HeadingReading>()
    private var lastStepNs = 0L
    private var lastDetectorNs = 0L
    var heading: HeadingReading? = null
        private set
    var detectorSteps = 0L
        private set
    var peakSteps = 0L
        private set
    var skippedSteps = 0L
        private set
    var usePeaks = false
        private set
    val offsetRad get() = estimator.offsetRad
    val steps get() = if (usePeaks) peakSteps else detectorSteps

    fun reset() {
        estimator.reset()
        heading = null
        history.clear()
        resetCounts()
    }

    fun resetCounts() {
        detectorSteps = 0
        peakSteps = 0
        skippedSteps = 0
        lastStepNs = 0
        lastDetectorNs = 0
        peaks.reset()
    }

    fun selectPeaks(enabled: Boolean) {
        usePeaks = enabled
        resetCounts()
    }

    fun align(mapBearingRad: Double, nowNs: Long): Boolean {
        val value = heading ?: return false
        if (nowNs - value.timestampNs !in 0..config.headingFreshnessNs) return false
        if (!estimator.align(mapBearingRad)) return false
        history.clear()
        heading = value.copy(mapRad = wrapHeading(value.relativeRad + estimator.offsetRad!!))
        history.addLast(heading!!)
        return true
    }

    fun rotation(timestampNs: Long, vector: FloatArray, screenRotation: Int) {
        val next = estimator.update(timestampNs, vector, screenRotation)
        heading = next
        if (next == null) {
            history.clear()
            return
        }
        history.addLast(next)
        while (history.isNotEmpty() &&
            timestampNs - history.first().timestampNs > config.headingHistoryNs
        ) {
            history.removeFirst()
        }
    }

    fun detector(timestampNs: Long): MotionEvent.Step? {
        if (timestampNs <= lastDetectorNs) return null
        lastDetectorNs = timestampNs
        detectorSteps++
        return if (usePeaks) null else step(timestampNs, StepSource.STEP_DETECTOR)
    }

    fun acceleration(timestampNs: Long, values: FloatArray): MotionEvent.Step? {
        val peakNs = peaks.update(timestampNs, values) ?: return null
        peakSteps++
        return if (usePeaks) step(peakNs, StepSource.ACCEL_PEAK) else null
    }

    private fun step(timestampNs: Long, source: StepSource): MotionEvent.Step? {
        if (timestampNs <= lastStepNs) return null
        lastStepNs = timestampNs
        val value = history.lastOrNull { it.timestampNs <= timestampNs }
        if (value?.mapRad == null || timestampNs - value.timestampNs > config.headingFreshnessNs) {
            skippedSteps++
            return null
        }
        return MotionEvent.Step(timestampNs, value.mapRad, source)
    }
}
