package edu.wisc.maplens

import edu.wisc.maplens.mapparse.PosterSighting
import edu.wisc.maplens.mapparse.SightingProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AimGuideTest {
    private val box = doubleArrayOf(200.0, 300.0, 760.0, 300.0, 760.0, 700.0, 200.0, 700.0)

    private fun seen(
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        fill: Double = 0.7,
        corners: DoubleArray = box,
    ) = PosterSighting(960, 1280, corners, null, yaw, pitch, fill)

    private fun AimGuide.settle(sighting: PosterSighting?, turnRate: Double = 0.0): AimState {
        var state = update(sighting, 0)
        for (t in 1..20) state = update(sighting, t * 100L, turnRate)
        return state
    }

    @Test
    fun `a straight-on map is ready`() {
        val state = AimGuide().settle(seen(yaw = 3.0, pitch = -4.0))
        assertEquals(AimHint.READY, state.hint)
        assertEquals(AimTone.GOOD, state.tone)
    }

    @Test
    fun `the Oct 12 left and down shots ask to move`() {
        // Angles measured from the hand-labelled corners of the fixture photos.
        assertEquals(AimHint.STEP_RIGHT, AimGuide().settle(seen(yaw = -28.7)).hint)
        assertEquals(AimHint.STEP_RIGHT, AimGuide().settle(seen(yaw = -23.0)).hint)
        assertEquals(AimHint.STEP_LEFT, AimGuide().settle(seen(yaw = 36.3)).hint)
        assertEquals(AimHint.LOWER, AimGuide().settle(seen(pitch = -33.0)).hint)
        assertEquals(AimTone.WARN, AimGuide().settle(seen(yaw = -28.7)).tone)
    }

    @Test
    fun `a slight angle is a gentle hint, not a warning`() {
        val state = AimGuide().settle(seen(pitch = 13.0))
        assertEquals(AimHint.RAISE, state.hint)
        assertEquals(AimTone.NEUTRAL, state.tone)
    }

    @Test
    fun `hysteresis keeps the warning until clearly fixed`() {
        val guide = AimGuide()
        guide.settle(seen(yaw = -25.0))
        assertEquals(AimTone.WARN, guide.settle(seen(yaw = -17.0)).tone)
        assertEquals(AimTone.NEUTRAL, guide.settle(seen(yaw = -15.0)).tone)
    }

    @Test
    fun `move direction points where to go`() {
        val state = AimGuide().settle(seen(yaw = -20.0, pitch = -5.0))
        assertTrue(state.moveX > 0, "step right")
        assertTrue(state.moveY > 0, "lower the phone")
    }

    @Test
    fun `a small map asks to move closer`() {
        assertEquals(AimHint.MOVE_CLOSER, AimGuide().settle(seen(fill = 0.4)).hint)
    }

    @Test
    fun `a corner at the edge asks to step back`() {
        val clipped = box.copyOf().also { it[0] = 4.0 }
        assertEquals(AimHint.STEP_BACK, AimGuide().settle(seen(corners = clipped)).hint)
        val cut = PosterSighting(960, 1280, null, SightingProblem.CUT_OFF)
        assertEquals(AimHint.STEP_BACK, AimGuide().settle(cut).hint)
    }

    @Test
    fun `turning fast asks to hold steady`() {
        assertEquals(AimHint.HOLD_STEADY, AimGuide().settle(seen(), turnRate = 1.2).hint)
    }

    @Test
    fun `one missed look keeps the last hint`() {
        val guide = AimGuide()
        guide.settle(seen())
        val missed = PosterSighting(960, 1280, null, SightingProblem.NOT_FOUND)
        assertEquals(AimHint.READY, guide.update(missed, 2_300).hint)
        assertEquals(AimHint.FIND, guide.update(missed, 3_000).hint)
    }

    @Test
    fun `smoothing damps a one-off bad corner`() {
        val guide = AimGuide()
        guide.settle(seen())
        assertEquals(AimHint.READY, guide.update(seen(yaw = 27.0), 2_100).hint)
    }
}
