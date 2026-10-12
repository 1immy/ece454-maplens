package edu.wisc.maplens.tracking

import kotlin.test.Test
import kotlin.test.assertEquals

/** Numbers taken straight from the proposal slides. */
class StepLengthTest {
    @Test
    fun `calibration walk gives 25 px per step`() {
        assertEquals(25.0, StepLength.calibrate(pixels = 400.0, steps = 16), 1e-9)
    }

    @Test
    fun `landmark refinement blends 70-30`() {
        val observed = StepLength.calibrate(pixels = 414.0, steps = 18)
        assertEquals(23.0, observed, 1e-9)
        assertEquals(24.4, StepLength.refine(25.0, observed, keepWeight = 0.7), 1e-9)
    }

    @Test
    fun `1000 px is about 28 m`() {
        assertEquals(28.0, StepLength.pixelsToMeters(1000.0, k = 25.0, strideMeters = 0.7), 1e-9)
    }
}
