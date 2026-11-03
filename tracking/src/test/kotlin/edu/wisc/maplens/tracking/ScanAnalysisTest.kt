package edu.wisc.maplens.tracking

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanAnalysisTest {
    // Quaternions as (x, y, z, w); rotations are right-handed about world axes.
    private fun axis(ax: Double, ay: Double, az: Double, deg: Double): DoubleArray {
        val half = Math.toRadians(deg) / 2
        return doubleArrayOf(ax * sin(half), ay * sin(half), az * sin(half), cos(half))
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    /** Upright portrait, camera turned [yawDeg] clockwise, tilted up [pitchDeg]. */
    private fun upright(yawDeg: Double, pitchDeg: Double = 0.0, rightRaisedDeg: Double = 0.0) =
        mul(
            axis(0.0, 0.0, 1.0, -yawDeg),
            mul(axis(0.0, 1.0, 0.0, -rightRaisedDeg), axis(1.0, 0.0, 0.0, 90.0 + pitchDeg)),
        ).map { it.toFloat() }.toFloatArray()

    private fun assertDeg(expected: Double, actualRad: Double, tol: Double = 0.5) =
        assertEquals(expected, Math.toDegrees(actualRad), tol)

    @Test
    fun `camera pose follows yaw, pitch and roll`() {
        val pose = assertNotNull(CameraPose.fromRotationVector(upright(30.0, 10.0, 5.0)))
        assertDeg(30.0, pose.yawRad, 1.0)
        assertDeg(10.0, pose.pitchRad, 1.0)
        assertDeg(5.0, pose.rollRad, 1.0)
        assertDeg(-120.0, CameraPose.fromRotationVector(upright(-120.0))!!.yawRad)
    }

    @Test
    fun `camera pose matches the heading estimator for an upright phone`() {
        val vector = upright(47.0)
        val heading = HeadingEstimator().update(1, vector)!!
        assertEquals(heading.relativeRad, CameraPose.fromRotationVector(vector)!!.yawRad, 1e-3)
    }

    @Test
    fun `a camera pointing at the floor has no yaw`() {
        assertNull(CameraPose.fromRotationVector(floatArrayOf(0f, 0f, 0f, 1f)))
    }

    @Test
    fun `columns map to bearings through the field of view`() {
        val frame =
            ScanFrame(CameraPose(Math.toRadians(10.0), 0.0, 0.0), 400, 300, Math.toRadians(60.0))
        assertDeg(10.0, frame.bearingOfColumn(200.0))
        assertDeg(40.0, frame.bearingOfColumn(400.0))
        assertDeg(-20.0, frame.bearingOfColumn(0.0))
    }

    /** White image with dark lines from the vanishing point to points along the border. */
    private fun corridor(w: Int, h: Int, vpx: Double, vpy: Double): FloatArray {
        val gray = FloatArray(w * h) { 1f }
        val ends = (0..8).flatMap { k ->
            val t = k / 8.0
            listOf(t * w to 0.0, t * w to h - 1.0, 0.0 to t * h, w - 1.0 to t * h)
        }
        for ((ex, ey) in ends) {
            for (s in 0..2000) {
                val u = s / 2000.0
                val x = (vpx + u * (ex - vpx)).roundToInt()
                val y = (vpy + u * (ey - vpy)).roundToInt()
                // Skip the clutter right at the vanishing point.
                if (u < 0.1) continue
                for (dx in 0..1) {
                    if (x + dx in 0 until w && y in 0 until h) gray[y * w + x + dx] = 0f
                }
            }
        }
        return gray
    }

    private val level = CameraPose(0.0, 0.0, 0.0)
    private val finder = VanishingPointFinder()

    @Test
    fun `finds a corridor vanishing point in view`() {
        val frame = ScanFrame(level, 160, 120, Math.toRadians(60.0))
        val vp = assertNotNull(finder.find(corridor(160, 120, 110.0, 60.0), frame))
        assertDeg(Math.toDegrees(atan(30.0 / frame.focalPx)), vp.angleRad, 2.0)
        assertTrue(vp.inView)
    }

    @Test
    fun `a vanishing point beside the view still gives the corridor axis`() {
        val frame = ScanFrame(level, 160, 120, Math.toRadians(60.0))
        val vp = assertNotNull(finder.find(corridor(160, 120, -150.0, 60.0), frame))
        assertDeg(Math.toDegrees(atan(-230.0 / frame.focalPx)), vp.angleRad, 3.0)
        assertTrue(!vp.inView)
    }

    @Test
    fun `tilting up moves the horizon down the picture`() {
        val pose = CameraPose(0.0, Math.toRadians(10.0), 0.0)
        val frame = ScanFrame(pose, 160, 120, Math.toRadians(60.0))
        val row = frame.horizonRow(80.0)
        assertTrue(row > 60.0)
        val vp = assertNotNull(finder.find(corridor(160, 120, 60.0, row), frame))
        assertDeg(Math.toDegrees(atan(-20.0 / frame.focalPx)), vp.angleRad, 2.0)
    }

    @Test
    fun `blank walls, door frames and noise give no vanishing point`() {
        val frame = ScanFrame(level, 160, 120, Math.toRadians(60.0))
        assertNull(finder.find(FloatArray(160 * 120) { 0.5f }, frame))
        val stripes = FloatArray(160 * 120) { if ((it % 160) / 20 % 2 == 0) 0f else 1f }
        assertNull(finder.find(stripes, frame))
        val random = Random(7)
        assertNull(finder.find(FloatArray(160 * 120) { random.nextFloat() }, frame))
    }

    @Test
    fun `room numbers survive common OCR confusions`() {
        val labels = setOf("3445", "3539A", "3415")
        assertEquals("3445", matchRoomLabel("RM 3445", labels))
        assertEquals("3539A", matchRoomLabel("3S39A", labels))
        assertEquals("3415", matchRoomLabel("34I5", labels))
        assertEquals("3445", matchRoomLabel("344S", labels))
        assertNull(matchRoomLabel("EXIT", labels))
        assertNull(matchRoomLabel("3999", labels))
    }

    @Test
    fun `a photo of a posted map means map up, not a crowd of doors`() {
        val frame =
            ScanFrame(CameraPose(Math.toRadians(92.0), 0.0, 0.0), 400, 300, Math.toRadians(60.0))
        val poster = listOf("3446", "3444 3445", "3556", "Fire Alarm").map { TextHit(it, 200.0) }
        val obs = scanObservations(frame, null, poster, setOf("3446", "3444", "3445", "3556"))
        val up = obs.single() as OrientObservation.MapUp
        assertDeg(92.0, up.relativeRad)
    }

    @Test
    fun `a photo becomes axis, opening and door observations`() {
        val frame =
            ScanFrame(CameraPose(Math.toRadians(90.0), 0.0, 0.0), 400, 300, Math.toRadians(60.0))
        val obs = scanObservations(
            frame,
            VanishingPoint(Math.toRadians(5.0), 0.5, inView = true),
            listOf(TextHit("3445", 400.0), TextHit("3445", 390.0), TextHit("HELLO", 10.0)),
            setOf("3445"),
        )
        assertEquals(3, obs.size)
        assertDeg(95.0, obs.filterIsInstance<OrientObservation.CorridorAxis>().single().relativeRad)
        assertDeg(95.0, obs.filterIsInstance<OrientObservation.Opening>().single().relativeRad)
        val door = obs.filterIsInstance<OrientObservation.Door>().single()
        assertEquals("3445", door.label)
        assertTrue(abs(Math.toDegrees(door.relativeRad) - 120.0) < 0.5)
    }
}
