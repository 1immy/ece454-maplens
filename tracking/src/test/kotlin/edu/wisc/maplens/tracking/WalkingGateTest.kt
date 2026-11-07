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
        val end = startNs + (seconds * 1e9).toLong()
        var nextStep = startNs + stepEveryNs
        var t = startNs
        while (t <= end) {
            val phase = 2 * PI * shakeHz * (t - startNs) / 1e9
            gate.rotation(t, upright)
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
    fun `the first supported footfall passes without waiting for another step`() {
        val gate = WalkingGate()
        val steps = run(gate, seconds = 0.6, vertical = 2.0, sideways = 1.0, spin = 0.5)
        assertEquals(1_600_000_000L, steps.single().timestampNanos)
        assertEquals(0L, gate.rejected)
    }

    @Test
    fun `light slow footfalls pass when the phone has little rotation and sway`() {
        val gate = WalkingGate()
        val steps = run(
            gate,
            seconds = 4.0,
            vertical = 0.50,
            sideways = 0.38,
            spin = 0.4,
            shakeHz = 1.0,
            stepEveryNs = 1_000_000_000L,
        )
        assertEquals(4, steps.size)
        assertEquals(2_000_000_000L, steps.first().timestampNanos)
    }

    @Test
    fun `low vertical motion still rejects mostly sideways hand movement`() {
        val gate = WalkingGate()
        val steps = run(gate, seconds = 4.0, vertical = 0.50, sideways = 1.4, spin = 0.4)
        assertTrue(steps.isEmpty())
        assertEquals(GaitVerdict.TOO_SIDEWAYS, gate.lastVerdict)
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
    fun `a single upward bump does not move the user`() {
        val gate = WalkingGate()
        val t = 1_000_000_000L
        for (i in 0..30) {
            val ts = t + i * sampleNs
            gate.rotation(ts, upright)
            gate.acceleration(ts, floatArrayOf(0f, 0f, if (i > 25) 13f else 9.81f))
            gate.gyroscope(ts, floatArrayOf(0f, 0f, 0f))
        }
        assertTrue(
            gate.offer(MotionEvent.Step(t + 30 * sampleNs, 0.0, StepSource.STEP_DETECTOR)).isEmpty()
        )
        assertEquals(GaitVerdict.ISOLATED_BUMP, gate.lastVerdict)
    }

    @Test
    fun `large vertical shaking is rejected even when the phone barely rotates`() {
        val gate = WalkingGate()
        val steps = run(gate, seconds = 6.0, vertical = 9.0, sideways = 0.1, spin = 0.1)
        assertTrue(steps.isEmpty())
        assertEquals(GaitVerdict.VERTICAL_SHAKE, gate.lastVerdict)
    }

    @Test
    fun `fast vertical oscillation is rejected even at walking amplitude`() {
        val gate = WalkingGate()
        val steps =
            run(gate, seconds = 6.0, vertical = 2.0, sideways = 0.1, spin = 0.1, shakeHz = 7.0)
        assertTrue(steps.isEmpty())
        assertEquals(GaitVerdict.VERTICAL_SHAKE, gate.lastVerdict)
    }

    @Test
    fun `a steady phone gives no bob and no steps`() {
        val gate = WalkingGate()
        val steps = run(gate, seconds = 6.0, vertical = 0.1, sideways = 0.1, spin = 0.05)
        assertTrue(steps.isEmpty())
        assertEquals(GaitVerdict.TOO_LITTLE_BOB, gate.lastVerdict)
    }

    @Test
    fun `a pause does not delay the first supported footfall on restart`() {
        val gate = WalkingGate()
        run(gate, seconds = 4.0, vertical = 2.0, sideways = 1.0, spin = 0.5)
        val resumed = run(
            gate,
            seconds = 0.6,
            vertical = 2.0,
            sideways = 1.0,
            spin = 0.5,
            startNs = 10_000_000_000L,
        )
        assertEquals(10_600_000_000L, resumed.single().timestampNanos)
    }

    @Test
    fun `turned off, the gate passes every step`() {
        val gate = WalkingGate(TrackingConfig(gaitGateEnabled = false))
        val steps =
            run(gate, seconds = 6.0, vertical = 1.0, sideways = 9.0, spin = 5.0, shakeHz = 4.0)
        assertEquals(10, steps.size)
    }

    @Test
    fun `missing or stale motion evidence cannot move the user`() {
        val gate = WalkingGate()
        val unsupported = MotionEvent.Step(stepPeriodNs, 0.0, StepSource.STEP_DETECTOR)
        assertTrue(gate.offer(unsupported).isEmpty())
        assertEquals(GaitVerdict.INSUFFICIENT_DATA, gate.lastVerdict)
        run(gate, seconds = 0.6, vertical = 2.0, sideways = 1.0, spin = 0.5)
        assertTrue(gate.offer(unsupported.copy(timestampNanos = 2_100_000_000L)).isEmpty())
        assertEquals(GaitVerdict.INSUFFICIENT_DATA, gate.lastVerdict)
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
            val raw = csv.reader().use { TraceReplay.replay(it, offset).steps }
            val gated = csv.reader().use {
                TraceReplay.replay(
                    it,
                    offset,
                    walkingGate = true
                ).steps
            }
            assertTrue(
                gated.size >= raw.size * 0.97,
                "$name: kept ${gated.size} of ${raw.size} steps"
            )
            assertEquals(raw.first(), gated.first(), "$name: first supported footfall")
        }
    }
}
