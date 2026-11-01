package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.StepSource

/**
 * Combines heading and step sources into the `MotionEvent.Step`s the tracker consumes (#12, #13).
 *
 * Two step sources are always counted side by side so they can be compared, but only one, the
 * "selected" source, emits events: the Android step detector by default, or experimental
 * accelerometer peaks ([selectPeaks]) on phones without one.
 *
 * Android can deliver step-detector events well after the footfall, so a step must not take the
 * heading at delivery time: just after a turn, that would place it on the wrong corridor. Instead a short heading [history] is kept and each step takes the last heading
 * at or before its footfall timestamp.
 *
 * Not thread safe: the recorder's single sensor worker owns it.
 */
class SensorMotionProcessor(private val config: TrackingConfig = TrackingConfig()) {
    private val estimator = HeadingEstimator(config)
    private val peaks = AccelerationStepDetector(config)

    /** Headings from the last `headingHistoryNs`, oldest first, for footfall lookups. */
    private val history = ArrayDeque<HeadingReading>()

    /** Last emitted step, so duplicates or out-of-order events never move the tracker twice. */
    private var lastStepNs = 0L
    private var lastDetectorNs = 0L

    /** Latest heading; null while the pose is ambiguous (see [HeadingEstimator.update]). */
    var heading: HeadingReading? = null
        private set
    var detectorSteps = 0L
        private set
    var peakSteps = 0L
        private set

    /** Selected-source steps dropped because no fresh, aligned heading existed at footfall. */
    var skippedSteps = 0L
        private set
    var usePeaks = false
        private set
    val offsetRad get() = estimator.offsetRad

    /** Count for the selected source, i.e. the steps the tracker would have received. */
    val steps get() = if (usePeaks) peakSteps else detectorSteps

    /** Full reset, including heading alignment; used when sensors re-register. */
    fun reset() {
        estimator.reset()
        heading = null
        history.clear()
        resetCounts()
    }

    /** New recording or source: zero the counts but keep the heading and its alignment. */
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

    /**
     * Aligns the current heading to [mapBearingRad]. Refuses a heading older than
     * `headingFreshnessNs`, which would anchor θ0 to a stale pose. History is restarted so no
     * step can take a heading from before the alignment, when mapRad was unknown or different.
     */
    fun align(mapBearingRad: Double, nowNs: Long): Boolean {
        val value = heading ?: return false
        if (nowNs - value.timestampNs !in 0..config.headingFreshnessNs) return false
        if (!estimator.align(mapBearingRad)) return false
        history.clear()
        heading = value.copy(mapRad = wrapHeading(value.relativeRad + estimator.offsetRad!!))
        history.addLast(heading!!)
        return true
    }

    /** Game-rotation-vector event: update the heading and trim history to its time window. */
    fun rotation(timestampNs: Long, vector: FloatArray, screenRotation: Int) {
        val next = estimator.update(timestampNs, vector, screenRotation)
        heading = next
        if (next == null) {
            // An ambiguous pose breaks continuity; steps during it are skipped, not guessed.
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

    /** Android step-detector event; its timestamp is the footfall, not the delivery time. */
    fun detector(timestampNs: Long): MotionEvent.Step? {
        if (timestampNs <= lastDetectorNs) return null
        lastDetectorNs = timestampNs
        detectorSteps++
        return if (usePeaks) null else step(timestampNs, StepSource.STEP_DETECTOR)
    }

    /** Accelerometer sample; always counted, but emits a step only when peaks are selected. */
    fun acceleration(timestampNs: Long, values: FloatArray): MotionEvent.Step? {
        val peakNs = peaks.update(timestampNs, values) ?: return null
        peakSteps++
        return if (usePeaks) step(peakNs, StepSource.ACCEL_PEAK) else null
    }

    /**
     * Pairs a footfall with the heading in effect at that moment. Skips (and counts) the step
     * when that heading is unaligned, ambiguous, or more than `headingFreshnessNs` old, since a
     * step with a wrong direction would do the tracker more harm than a missing one.
     */
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
