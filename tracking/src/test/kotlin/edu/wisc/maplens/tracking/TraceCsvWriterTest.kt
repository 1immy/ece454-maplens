package edu.wisc.maplens.tracking

import java.io.StringWriter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TraceCsvWriterTest {
    @Test
    fun `all sensor rows preserve timestamps components and empty fields`() {
        val output = StringWriter()
        TraceCsvWriter(output).use { csv ->
            csv.append(10, TraceSensor.STEP, floatArrayOf(1f))
            csv.append(11, TraceSensor.STEP_COUNTER, floatArrayOf(2f))
            csv.append(12, TraceSensor.ACCEL, floatArrayOf(1f, -2f, 9.8f))
            csv.append(13, TraceSensor.GYRO, floatArrayOf(0f, 0.5f, -1f))
            csv.append(14, TraceSensor.GAME_RV, floatArrayOf(0f, 0f, 0f, 1f))
            assertEquals(5L, csv.rows)
        }
        assertEquals(
            "timestamp_ns,type,v0,v1,v2,v3,label\n" +
                "10,step,1.0,,,,\n11,step_counter,2.0,,,,\n" +
                "12,accel,1.0,-2.0,9.8,,\n13,gyro,0.0,0.5,-1.0,,\n" +
                "14,game_rv,0.0,0.0,0.0,1.0,\n",
            output.toString(),
        )
    }

    @Test
    fun `CSV uses decimal points even on a comma-decimal locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val output = StringWriter()
            TraceCsvWriter(output).use { it.append(1, TraceSensor.STEP, floatArrayOf(1.5f)) }
            assertEquals("1,step,1.5,,,,", output.toString().lines()[1])
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `invalid samples do not corrupt the trace`() {
        val output = StringWriter()
        TraceCsvWriter(output).use { csv ->
            assertFailsWith<IllegalArgumentException> {
                csv.append(1, TraceSensor.ACCEL, floatArrayOf(1f))
            }
            assertFailsWith<IllegalArgumentException> {
                csv.append(1, TraceSensor.STEP, floatArrayOf(Float.NaN))
            }
            assertFailsWith<IllegalArgumentException> {
                csv.append(-1, TraceSensor.STEP, floatArrayOf(1f))
            }
            assertEquals(0L, csv.rows)
        }
        assertEquals(1, output.toString().trimEnd().lines().size)
    }

    @Test
    fun `counter excludes steps before the session and resets each session`() {
        val counter = SessionStepCounter()
        assertEquals(0f, counter.observe(900f))
        assertEquals(3f, counter.observe(903f))
        counter.reset(910f)
        assertEquals(2f, counter.observe(912f))
        counter.reset()
        assertEquals(0f, counter.observe(920f))
        assertEquals(0f, counter.observe(1f))
        assertEquals(2f, counter.observe(3f))
    }
}
