package edu.wisc.maplens.mapparse

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterAimTest {
    private val focal = 1000.0
    private val cx = 360.0
    private val cy = 480.0

    /** A 1.2 × 0.8 m map 2 m away, turned [yaw] about the vertical and [pitch] about the level. */
    private fun seen(yaw: Double, pitch: Double): DoubleArray {
        val a = Math.toRadians(yaw)
        val b = Math.toRadians(pitch)
        return listOf(-0.6 to -0.4, 0.6 to -0.4, 0.6 to 0.4, -0.6 to 0.4).flatMap { (x, y) ->
            // Turn about y (yaw), then about x (pitch); positive values bring the left
            // (respectively top) edge nearer, as when the phone stands left of (above) the map.
            val x1 = x * cos(a)
            val z1 = x * sin(a)
            val y2 = y * cos(b)
            val z2 = z1 + y * sin(b)
            val z = 2.0 + z2
            listOf(cx + focal * x1 / z, cy + focal * y2 / z)
        }.toDoubleArray()
    }

    @Test
    fun aParallelMapReadsZero() {
        val (yaw, pitch) = wallFacing(seen(0.0, 0.0), cx, cy, focal)
        assertEquals(0.0, yaw, 1e-6)
        assertEquals(0.0, pitch, 1e-6)
    }

    @Test
    fun recoversTurnsAndTheirSides() {
        for ((turn, tilt) in listOf(
            25.0 to 0.0,
            -15.0 to 0.0,
            0.0 to 30.0,
            0.0 to -12.0,
            20.0 to 10.0
        )) {
            val (yaw, pitch) = wallFacing(seen(turn, tilt), cx, cy, focal)
            // Phone left of the map reads negative yaw; phone above it, negative pitch.
            assertEquals("yaw for $turn/$tilt", -turn, yaw, 1.5)
            assertEquals("pitch for $turn/$tilt", -tilt, pitch, 1.5)
        }
    }

    /** The hand-labelled fixture shot "from the left" (iPhone main camera, 26 mm equivalent). */
    @Test
    fun theLeftThirtyFixtureIsFromTheLeft() {
        val corners = doubleArrayOf(486.6, 930.2, 2230.1, 1236.2, 2206.9, 2544.0, 464.4, 2711.9)
        val focal = 26 / 43.27 * kotlin.math.hypot(3024.0, 4032.0)
        val (yaw, pitch) = wallFacing(corners, 1512.0, 2016.0, focal)
        assertTrue("yaw $yaw", yaw < -20)
        assertTrue("pitch $pitch", abs(pitch) < 5)
    }
}
