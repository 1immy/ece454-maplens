package edu.wisc.maplens.tracking

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadingEstimatorTest {
    private fun pose(pitch: Double, yaw: Double): FloatArray {
        val x = pitch / 2
        val z = yaw / 2
        return floatArrayOf(
            (sin(x) * cos(z)).toFloat(),
            (sin(x) * sin(z)).toFloat(),
            (cos(x) * sin(z)).toFloat(),
            (cos(x) * cos(z)).toFloat()
        )
    }

    @Test
    fun `reading tilt from flat to upright preserves heading across yaw angles`() {
        for (pitch in listOf(0.0, 15.0, 45.0, 75.0, 90.0)) {
            for (yaw in listOf(-179.0, -90.0, 0.0, 90.0, 179.0)) {
                val reading = HeadingEstimator().update(
                    1,
                    pose(Math.toRadians(pitch), Math.toRadians(yaw))
                )!!
                assertEquals(-Math.toRadians(yaw), reading.relativeRad, 1e-6)
            }
        }
    }

    @Test
    fun `landscape and reverse display axes preserve forward direction`() {
        for (pitch in listOf(0.0, PI / 4, PI / 2)) {
            for (rotation in 0..3) {
                val q = pose(pitch, 0.0)
                val s = sin(-rotation * PI / 4)
                val c = cos(-rotation * PI / 4)
                val turned = floatArrayOf(
                    (q[0] * c + q[1] * s).toFloat(),
                    (q[1] * c - q[0] * s).toFloat(),
                    (q[2] * c + q[3] * s).toFloat(),
                    (q[3] * c - q[2] * s).toFloat(),
                )
                val reading = HeadingEstimator().update(1, turned, rotation)!!
                assertEquals(0.0, reading.relativeRad, 1e-6)
            }
        }
    }

    @Test
    fun `alignment maps clockwise bearings and smoothing crosses wrap by shortest path`() {
        val estimator = HeadingEstimator()
        estimator.update(1, pose(0.0, -Math.toRadians(179.0)))
        assertTrue(estimator.align(PI / 2))
        val reading = estimator.update(20_000_001, pose(0.0, Math.toRadians(179.0)))!!
        assertTrue(reading.relativeRad > Math.toRadians(179.0))
        assertTrue(reading.mapRad!! > PI / 2)
        estimator.reset()
        assertFalse(estimator.align(0.0))
    }

    @Test
    fun `invalid quaternion ambiguous projection and face down never fabricate heading`() {
        assertNull(HeadingEstimator().update(1, floatArrayOf(0f, 0f, 0f, 0f)))
        assertNull(HeadingEstimator().update(1, floatArrayOf(Float.NaN, 0f, 0f)))
        assertNull(HeadingEstimator().update(1, pose(-PI / 4, 0.0)))
        assertNull(HeadingEstimator().update(1, pose(PI, 0.0)))
        assertNotNull(HeadingEstimator().update(1, floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun `quaternion sign does not affect heading and moderate roll stays usable`() {
        val q = floatArrayOf(0f, sin(PI / 8).toFloat(), 0f, cos(PI / 8).toFloat())
        val a = HeadingEstimator().update(1, q)!!
        val b = HeadingEstimator().update(1, q.map { -it }.toFloatArray())!!
        assertEquals(a.relativeRad, b.relativeRad, 1e-6)
        assertTrue(a.relativeRad.isFinite())
    }
}
