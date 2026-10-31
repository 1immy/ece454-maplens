package edu.wisc.maplens.tracking

import java.io.Closeable
import java.io.Writer
import java.util.Locale

/** Raw inputs, deliberately separate from the navigation model's processed MotionEvent. */
enum class TraceSensor(val wireName: String, val components: Int) {
    STEP("step", 1),
    STEP_COUNTER("step_counter", 1),
    ACCEL("accel", 3),
    GYRO("gyro", 3),
    GAME_RV("game_rv", 4),
}

/** One worker owns this streaming writer. Timestamps remain in Android's boot-time clock. */
class TraceCsvWriter(private val writer: Writer) : Closeable {
    var rows: Long = 0
        private set

    init {
        writer.write("timestamp_ns,type,v0,v1,v2,v3,label\n")
    }

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

private val ROOM = Regex("[0-9]{3,4}[A-Z]?")
private val CHECKPOINT_LABEL = Regex("door-${ROOM.pattern}")

/** Room numbers as printed on signs and graph landmarks ("3539a" -> "door-3539A"), else null. */
fun checkpointLabel(room: String): String? =
    room.trim().uppercase(Locale.ROOT).takeIf(ROOM::matches)?.let { "door-$it" }

/** Keep only session-relative counter values, rather than the user's since-boot history. */
class SessionStepCounter {
    private var baseline: Float? = null

    fun reset(lastReading: Float? = null) {
        baseline = lastReading
    }

    fun observe(total: Float): Float {
        require(total.isFinite() && total >= 0)
        if (baseline == null || total < baseline!!) baseline = total
        return total - baseline!!
    }
}
