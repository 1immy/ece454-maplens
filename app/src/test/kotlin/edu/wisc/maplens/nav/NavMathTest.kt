package edu.wisc.maplens.nav

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class NavMathTest {
    @Test
    fun `lerpAngle takes the short way across the seam`() {
        val from = Math.toRadians(170.0)
        val to = Math.toRadians(-170.0)
        assertEquals(
            180.0,
            Math.toDegrees(NavMath.lerpAngle(from, to, 0.5)).let {
                kotlin.math.abs(it)
            },
            1e-9
        )
        assertEquals(-170.0, Math.toDegrees(NavMath.lerpAngle(from, to, 1.0)), 1e-9)
    }

    @Test
    fun `approach snaps at zero time constant and is frame-rate independent`() {
        assertEquals(1.0, NavMath.approach(0.016, 0.0))
        val oneFrame = NavMath.approach(1.0 / 30, 0.2)
        val twoFrames = 1 - (1 - NavMath.approach(1.0 / 60, 0.2)).let { it * it }
        assertEquals(oneFrame, twoFrames, 1e-12)
    }

    @Test
    fun `meters round like spoken distances`() {
        assertEquals(7, NavMath.roundedMeters(7.4))
        assertEquals(45, NavMath.roundedMeters(43.0))
        assertEquals(120, NavMath.roundedMeters(118.0))
        assertEquals(0, NavMath.roundedMeters(-3.0))
    }

    @Test
    fun `walking time is at least one minute until arrival`() {
        assertEquals(0, NavMath.walkingMinutes(0.0))
        assertEquals(1, NavMath.walkingMinutes(5.0))
        assertEquals(2, NavMath.walkingMinutes(100.0))
    }

    @Test
    fun `screen rotation puts the given bearing at the top`() {
        assertEquals(0.0, NavMath.screenRotationDeg(-PI / 2), 1e-9)
        assertEquals(-90.0, NavMath.screenRotationDeg(0.0), 1e-9)
        assertEquals(90.0, NavMath.screenRotationDeg(PI), 1e-9)
    }
}
