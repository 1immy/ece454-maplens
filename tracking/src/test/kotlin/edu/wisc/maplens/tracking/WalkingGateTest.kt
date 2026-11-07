package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.MotionEvent
import edu.wisc.maplens.model.StepSource
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalkingGateTest {
    /** Phone flat and still in its orientation: device axes are world axes. */
    private val upright = floatArrayOf(0f, 0f, 0f, 1f)
    private val stepPeriodNs = 600_000_000L
    private val sampleNs = 20_000_000L

    /**
     * Feeds [seconds] of sensor data and a detector step every [stepPeriodNs]. [vertical] and
     * [sideways] are the acceleration amplitudes (m/s²) at the step rate (walking) or
     * [shakeHz]; [spin] the rotation rate amplitude (rad/s).
     */
    private fun run(
        gate: WalkingGate,
        seconds: Double,
        vertical: Double,
        sideways: Double,
        spin: Double,
        shakeHz: Double = 1e9 / stepPeriodNs,
        startNs: Long = 1_000_000_000L,
        stepEveryNs: Long = stepPeriodNs,
    ): List<MotionEvent.Step> {
        val out = mutableListOf<MotionEvent.Step>()
        gate.rotation(upright)
        val end = startNs + (seconds * 1e9).toLong()
        var nextStep = startNs + stepEveryNs
        var t = startNs
        while (t <= end) {
            val phase = 2 * PI * shakeHz * (t - startNs) / 1e9
            gate.acceleration(
                t,
                floatArrayOf(
                    (sideways * sin(phase)).toFloat(),
                    (sideways * cos(phase)).toFloat(),
                    (9.81 + vertical * sin(phase)).toFloat(),
                ),
            )
            gate.gyroscope(t, floatArrayOf((spin * sin(phase)).toFloat(), 0f, 0f))
            if (t >= nextStep) {
                out += gate.offer(MotionEvent.Step(t, 0.0, StepSource.STEP_DETECTOR))
                nextStep += stepEveryNs
            }
            t += sampleNs
        }
        return out
    }

    @Test
    fun `walking passes every step once the walk has started`() {
        val gate = WalkingGate()
        val steps = run(gate, seconds = 6.0, vertical = 2.0, sideways = 1.0, spin = 0.5)
        // 10 detector steps in 6 s: the first 4 are held, then released together.
        assertEquals(10, steps.size)
        assertTrue(gate.isWalking)
        assertEquals(0, gate.rejected)
    }

    @Test
    fun `shaking in place never moves the user`() {
        val gate = WalkingGate()
        val steps =
            run(gate, seconds = 8.0, vertical = 1.0, sideways = 9.0, spin = 5.0, shakeHz = 4.0)
        assertTrue(steps.isEmpty(), "shaking produced ${steps.size} steps")
        assertTrue(gate.rejected > 0)
    }

    @Test
    fun `rotating the phone back and forth is not walking`() {
        val gate = WalkingGate()
        val steps = run(gate, seconds = 8.0, vertical = 2.0, sideways = 1.0, spin = 4.0)
        assertTrue(steps.isEmpty())
        assertEquals(GaitVerdict.TOO_MUCH_TURN, gate.lastVerdict)
    }

    @Test
    fun `a couple of bumps never start a walk`() {
        val gate = WalkingGate()
        // Two walking-like steps, then nothing: they stay held and are dropped.
        val steps = run(gate, seconds = 1.3, vertical = 2.0, sideways = 1.0, spin = 0.5)
        assertTrue(steps.isEmpty())
        assertTrue(!gate.isWalking)
    }

    @Test
    fun `a steady phone gives no bob and no steps`() {
        val gate = WalkingGate()
        val steps = run(gate, seconds = 6.0, vertical = 0.1, sideways = 0.1, spin = 0.05)
        assertTrue(steps.isEmpty())
        assertEquals(GaitVerdict.TOO_LITTLE_BOB, gate.lastVerdict)
    }

    @Test
    fun `a long pause ends the walk and the next one starts again`() {
        val gate = WalkingGate()
        run(gate, seconds = 4.0, vertical = 2.0, sideways = 1.0, spin = 0.5)
        assertTrue(gate.isWalking)
        val resumed = run(
            gate,
            seconds = 3.0,
            vertical = 2.0,
            sideways = 1.0,
            spin = 0.5,
            startNs = 10_000_000_000L,
        )
        assertEquals(5, resumed.size, "4 held then released, then 1 more")
    }

    @Test
    fun `turned off, the gate passes every step`() {
        val gate = WalkingGate(TrackingConfig(gaitGateEnabled = false))
        val steps =
            run(gate, seconds = 6.0, vertical = 1.0, sideways = 9.0, spin = 5.0, shakeHz = 4.0)
        assertEquals(10, steps.size)
    }

    @Test
    fun `missing sensors skip their check instead of blocking steps`() {
        val gate = WalkingGate()
        // No rotation vector, accelerometer or gyroscope at all: only the start rule applies.
        val steps = (1..8).flatMap { i ->
            gate.offer(MotionEvent.Step(i * stepPeriodNs, 0.0, StepSource.STEP_DETECTOR))
        }
        assertEquals(8, steps.size)
    }

    @Test
    fun `recorded EH3 walks keep nearly every step`() {
        val root = System.getProperty("maplens.fixtures")?.let(::File) ?: return
        val walks = listOf(
            "3446-01",
            "3446-02",
            "3446-03",
            "3434-04"
        ).map { "eh-3-elevators-3418-$it" }
        for (name in walks) {
            val csv = File(root, "traces/$name.csv")
            val json = File(root, "traces/$name.json")
            val isPointer = csv.exists() && csv.readText().startsWith("version https://git-lfs")
            if (!csv.exists() || !json.exists() || isPointer) {
                continue
            }
            val offset = Regex("\"headingOffsetRad\":\\s*(-?[0-9.Ee+-]+)")
                .find(json.readText())!!.groupValues[1].toDouble()
            val raw = TraceReplay.replay(csv.reader(), offset).steps.size
            val gated = TraceReplay.replay(csv.reader(), offset, walkingGate = true).steps.size
            assertTrue(gated >= raw * 0.97, "$name: kept $gated of $raw steps")
        }
    }
}
