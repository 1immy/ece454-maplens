package edu.wisc.maplens.tracking

import java.io.Closeable
import java.io.Writer
import java.util.Locale

/**
 * Raw input streams in a trace, deliberately separate from the processed `MotionEvent`s the
 * tracker consumes, so a recording can be replayed through improved processing later.
 *
 * @property wireName the CSV `type` column value.
 * @property components how many of v0..v3 the stream fills; the rest stay empty.
 */
enum class TraceSensor(val wireName: String, val components: Int) {
    STEP("step", 1),
    STEP_COUNTER("step_counter", 1),
    ACCEL("accel", 3),
    GYRO("gyro", 3),
    GAME_RV("game_rv", 4),
}

/**
 * Streams a trace in the shared seven-column format from `data/README.md`:
 * `timestamp_ns,type,v0,v1,v2,v3,label`. Android-free so it is unit tested on the JVM.
 *
 * Every row is validated before anything is written, so a bad sample throws and leaves the file
 * intact. Numbers use Kotlin's locale-independent `toString()` (a decimal point, sometimes an
 * exponent such as `1.0E-5`), so a phone set to a comma-decimal locale still writes valid CSV.
 *
 * Timestamps stay in Android's boot clock. Rows are in arrival order; each stream is
 * time-ordered, but streams can interleave slightly out of order, so sort by timestamp to
 * replay. One worker thread owns each writer.
 */
class TraceCsvWriter(private val writer: Writer) : Closeable {
    /** Data rows written so far, excluding the header; saved in the JSON for integrity checks. */
    var rows: Long = 0
        private set

    init {
        writer.write("timestamp_ns,type,v0,v1,v2,v3,label\n")
    }

    /** Writes one sensor sample; [values] must have exactly `sensor.components` finite floats. */
    fun append(timestampNs: Long, sensor: TraceSensor, values: FloatArray) {
        require(timestampNs >= 0)
        require(values.size == sensor.components && values.all { it.isFinite() })
        val fields = (0 until 4).map { values.getOrNull(it)?.toString().orEmpty() }
        writer.write("$timestampNs,${sensor.wireName},${fields.joinToString(",")},\n")
        rows++
    }

    /** A user tap at a known door; [label] must come from [checkpointLabel]. */
    fun appendCheckpoint(timestampNs: Long, label: String) {
        require(timestampNs >= 0 && CHECKPOINT_LABEL.matches(label))
        writer.write("$timestampNs,checkpoint,,,,,$label\n")
        rows++
    }

    override fun close() = writer.close()
}

/** A room number like 342, 3445 or 3539A; also keeps commas and newlines out of the CSV. */
private val ROOM = Regex("[0-9]{3,4}[A-Z]?")
private val CHECKPOINT_LABEL = Regex("door-${ROOM.pattern}")

/** Room numbers as printed on signs and graph landmarks ("3539a" -> "door-3539A"), else null. */
fun checkpointLabel(room: String): String? =
    room.trim().uppercase(Locale.ROOT).takeIf(ROOM::matches)?.let { "door-$it" }

/**
 * Converts Android's `TYPE_STEP_COUNTER` (steps since boot) into steps since the recording
 * started, so a trace never stores the user's step history.
 */
class SessionStepCounter {
    private var baseline: Float? = null

    /**
     * Starts a session. [lastReading] is the most recent preview value; without one, the first
     * reading after the reset becomes the baseline, so steps taken before it are not counted.
     */
    fun reset(lastReading: Float? = null) {
        baseline = lastReading
    }

    /** Returns steps since the baseline. A drop in the total means a reboot, so it re-bases. */
    fun observe(total: Float): Float {
        require(total.isFinite() && total >= 0)
        if (baseline == null || total < baseline!!) baseline = total
        return total - baseline!!
    }
}
