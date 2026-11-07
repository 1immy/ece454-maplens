package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.MotionEvent
import java.io.Reader

/** A door tap in a trace: `label` is `door-<room>`. */
data class TraceCheckpoint(val timestampNs: Long, val label: String)

/** What a recorded trace yields when run back through the live processing. */
data class ReplayedTrace(
    val steps: List<MotionEvent.Step>,
    val checkpoints: List<TraceCheckpoint>,
)

/**
 * Re-runs a recorded trace (format in `data/README.md`) through [SensorMotionProcessor], so
 * the tracker sees exactly the steps and headings the phone would have produced live.
 * Android-free, for unit tests and offline evaluation (#13, #18).
 */
object TraceReplay {
    /**
     * @param headingOffsetRad θ0 from the trace's JSON (`headingOffsetRad`); applied to the
     *   first heading, as the user's tap on "align" was before Start.
     * @param screenRotation display rotation during the walk; the EH3 traces are all 0.
     * @param walkingGate pass steps through [WalkingGate], as live tracking does; off by
     *   default so the raw detector steps can still be studied.
     */
    fun replay(
        csv: Reader,
        headingOffsetRad: Double,
        config: TrackingConfig = TrackingConfig(),
        screenRotation: Int = 0,
        walkingGate: Boolean = false,
    ): ReplayedTrace {
        val rows = csv.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }
        // Streams are interleaved in arrival order; replay them in measurement order.
        val ordered = rows.sortedBy { it[0].toLong() }
        val processor = SensorMotionProcessor(config)
        val gate = WalkingGate(config)
        val steps = mutableListOf<MotionEvent.Step>()
        val checkpoints = mutableListOf<TraceCheckpoint>()
        for (row in ordered) {
            val t = row[0].toLong()
            when (row[1]) {
                TraceSensor.GAME_RV.wireName -> {
                    val vector = FloatArray(4) { row[2 + it].toFloat() }
                    processor.rotation(t, vector, screenRotation)
                    gate.rotation(vector)
                    val heading = processor.heading
                    if (processor.offsetRad == null && heading != null) {
                        // Reproduces the recorded alignment: offset = map bearing − relative.
                        processor.align(wrapHeading(heading.relativeRad + headingOffsetRad), t)
                    }
                }
                TraceSensor.ACCEL.wireName -> gate.acceleration(t, values(row))
                TraceSensor.GYRO.wireName -> gate.gyroscope(t, values(row))
                TraceSensor.STEP.wireName -> processor.detector(t)?.let { step ->
                    if (walkingGate) steps += gate.offer(step) else steps += step
                }
                "checkpoint" -> checkpoints += TraceCheckpoint(t, row[6])
            }
        }
        return ReplayedTrace(steps, checkpoints)
    }

    private fun values(row: List<String>) = FloatArray(3) { row[2 + it].toFloat() }
}
