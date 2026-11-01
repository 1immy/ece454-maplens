package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.StepSource
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorMotionProcessorTest {
    @Test
    fun `delayed detector uses historical heading at footfall rather than delivery`() {
        val processor = SensorMotionProcessor()
        processor.rotation(1_000_000_000, floatArrayOf(0f, 0f, 0f, 1f), 0)
        assertTrue(processor.align(PI / 2, 1_000_000_000))
        processor.rotation(2_000_000_000, floatArrayOf(0f, 0f, -0.70710677f, 0.70710677f), 0)
        val step = processor.detector(1_100_000_000)!!
        assertEquals(PI / 2, step.headingRad, 1e-6)
        assertEquals(StepSource.STEP_DETECTOR, step.source)
        assertNull(processor.detector(1_100_000_000))
        assertEquals(1L, processor.detectorSteps)
    }

    @Test
    fun `unaligned stale and invalid headings suppress navigation but preserve counts`() {
        val processor = SensorMotionProcessor()
        assertNull(processor.detector(1_000_000_000))
        processor.rotation(1_000_000_000, floatArrayOf(0f, 0f, 0f, 1f), 0)
        assertFalse(processor.align(0.0, 2_000_000_000))
        assertTrue(processor.align(0.0, 1_000_000_000))
        assertNull(processor.detector(2_000_000_000))
        processor.rotation(2_100_000_000, floatArrayOf(0f, 0f, 0f, 0f), 0)
        assertNull(processor.detector(2_200_000_000))
        assertEquals(3L, processor.steps)
        assertEquals(3L, processor.skippedSteps)
    }

    @Test
    fun `selected source is exclusive and preview counts work without recording`() {
        val processor = SensorMotionProcessor()
        processor.selectPeaks(true)
        processor.detector(1_000_000_000)
        assertEquals(0L, processor.steps)
        val detector = AccelerationStepDetector()
        var count = 0
        for (sample in 1..500) {
            val t = sample * 20_000_000L
            val values =
                floatArrayOf(0f, 0f, (9.81 + 1.8 * sin(2 * PI * 2 * sample * 0.02)).toFloat())
            if (detector.update(t, values) != null) count++
            processor.acceleration(t, values)
        }
        assertTrue(count in 18..20)
        assertEquals(count.toLong(), processor.steps)
        processor.selectPeaks(false)
        assertEquals(0L, processor.steps)
        processor.detector(11_000_000_000)
        assertEquals(1L, processor.steps)
    }

    @Test
    fun `synthetic slow normal and brisk walking is insensitive to acceleration axis`() {
        for (hz in listOf(0.8, 1.6, 2.6)) {
            for (axis in 0..2) {
                val detector = AccelerationStepDetector()
                var count = 0
                for (sample in 1..500) {
                    val values = FloatArray(3)
                    values[axis] = (9.81 + 1.8 * sin(2 * PI * hz * sample * 0.02)).toFloat()
                    if (detector.update(sample * 20_000_000L, values) != null) count++
                }
                val expected = (hz * 10).toInt()
                assertTrue(count in expected - 2..expected + 1, "hz=$hz axis=$axis count=$count")
            }
        }
    }

    @Test
    fun `stationary noise rotation of gravity and restart produce no artificial steps`() {
        val detector = AccelerationStepDetector()
        for (sample in 1..500) {
            val angle = sample * 0.01
            val values = floatArrayOf(
                (9.81 * sin(angle)).toFloat(),
                (9.81 * kotlin.math.cos(angle)).toFloat(),
                0f
            )
            assertNull(detector.update(sample * 20_000_000L, values))
        }
        assertNull(detector.update(20_000_000_000, floatArrayOf(0f, 0f, 20f)))
        detector.reset()
        assertNull(detector.update(21_000_000_000, floatArrayOf(0f, 0f, 9.81f)))
    }
}
