package edu.wisc.maplens.nav

import edu.wisc.maplens.tracking.WalkingGate
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PuckMotionTest {
    private val k = 17.0
    private val frame = 1.0 / 60

    /** Plays [steps] (arrival time s, footfall ns) through a 60 Hz display; returns s per frame. */
    private fun play(
        motion: PuckMotion,
        steps: List<Pair<Double, Long>>,
        until: Double,
        stopAt: Double = Double.MAX_VALUE,
    ): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var next = 0
        var count = 0
        var t = 0.0
        while (t <= until) {
            while (next < steps.size && steps[next].first <= t) {
                count++
                motion.onStep(count * k, steps[next].first, k, steps[next].second)
                next++
            }
            motion.setMoving(t < stopAt)
            out += t to motion.advance(t)
            t += frame
        }
        return out
    }

    private fun steady(n: Int, every: Double = 0.6, start: Double = 0.5) =
        (0 until n).map { i -> (start + i * every) to ((start + i * every) * 1e9).toLong() }

    private fun maxFrameMove(track: List<Pair<Double, Double>>) =
        track.zipWithNext { a, b -> b.second - a.second }.maxOf { abs(it) }

    @Test
    fun `walking moves the dot every frame, never backwards and never in jumps`() {
        val motion = PuckMotion(snapPx = 4 * k).apply { reset(0.0) }
        val track = play(motion, steady(12), until = 7.5, stopAt = 7.5)
        val walking = track.filter { it.first in 1.2..6.8 }
        val moves = walking.zipWithNext { a, b -> b.second - a.second }
        assertTrue(moves.all { it > 0 }, "the dot paused while walking")
        // Not just creeping: every frame covers at least a third of walking pace (17 px / 0.6 s).
        val slowest = moves.minOf { it } / frame
        assertTrue(slowest > (k / 0.6) / 3, "slowed to $slowest px/s mid-walk")
        assertTrue(
            track.zipWithNext { a, b -> b.second - a.second }.all { it >= -1e-9 },
            "went back"
        )
        // One step is 17 px every 0.6 s, about 0.5 px per frame; nothing near a step-sized jump.
        assertTrue(maxFrameMove(track) < 1.5, "largest frame move ${maxFrameMove(track)} px")
    }

    @Test
    fun `the dot keeps pace with the tracker`() {
        val motion = PuckMotion(snapPx = 4 * k).apply { reset(0.0) }
        val track = play(motion, steady(12), until = 7.0)
        // Mid-walk the dot sits within about a step of the tracker's last fix.
        for ((t, s) in track.filter { it.first in 2.0..6.5 }) {
            val confirmed = steady(12).count { it.first <= t } * k
            assertTrue(abs(s - confirmed) <= 1.2 * k, "at $t s the dot is ${s - confirmed} px off")
        }
    }

    @Test
    fun `stopping halts the dot within half a second, at the confirmed position`() {
        val motion = PuckMotion(snapPx = 4 * k).apply { reset(0.0) }
        val track = play(motion, steady(8), until = 8.0, stopAt = 5.2)
        val atStop = track.first { it.first >= 5.7 }.second
        val later = track.last().second
        assertTrue(later - atStop < 0.5, "still moved ${later - atStop} px after stopping")
        assertTrue(abs(later - 8 * k) <= k, "rests at $later px, tracker says ${8 * k}")
    }

    @Test
    fun `two footfalls arriving together don't make it jump`() {
        val motion = PuckMotion(snapPx = 4 * k).apply { reset(0.0) }
        val steps = steady(10).toMutableList()
        // Steps 5 and 6 are delivered late, together (Android can batch detector events).
        steps[4] = (steps[5].first - 0.02) to steps[4].second
        val track = play(motion, steps.sortedBy { it.first }, until = 7.0)
        assertTrue(maxFrameMove(track) < 2.0, "largest frame move ${maxFrameMove(track)} px")
    }

    @Test
    fun `a big correction slides over quickly instead of teleporting`() {
        val motion = PuckMotion(snapPx = 4 * k).apply { reset(0.0) }
        motion.setMoving(true)
        motion.advance(0.0)
        motion.onStep(10 * k, 0.0, k, 0)
        val track = (1..60).map { i -> motion.advance(i * frame) }
        assertTrue(track.first() < 5 * k, "jumped straight to ${track.first()}")
        assertTrue(track.last() > 9 * k, "only reached ${track.last()} after 1 s")
    }

    @Test
    fun `no footfalls means no motion`() {
        val motion = PuckMotion(snapPx = 4 * k).apply { reset(5.0) }
        val track = play(motion, emptyList(), until = 3.0)
        assertEquals(5.0, track.last().second, 1e-9)
    }

    /**
     * The recorded EH3 walks, played back as live: footfall timing from the trace, and "still
     * moving" from the same [WalkingGate] bob signal the app uses, fed the trace's sensors.
     */
    @Test
    fun `recorded walks play back smoothly`() {
        val root = System.getProperty("maplens.fixtures")?.let(::File) ?: return
        for (name in listOf("3446-01", "3446-02", "3446-03").map { "eh-3-elevators-3418-$it" }) {
            val csv = File(root, "traces/$name.csv")
            if (!csv.exists() || csv.readText().startsWith("version https://git-lfs")) continue
            val rows = csv.readLines().drop(1).map { it.split(',') }
                .filter { it.size > 4 }.sortedBy { it[0].toLong() }
            val gate = WalkingGate()
            val motion = PuckMotion(snapPx = 4 * k).apply { reset(0.0) }
            val t0 = rows.first()[0].toLong()
            val end = rows.last()[0].toLong() + 2_000_000_000L
            var row = 0
            var count = 0
            var frameNs = t0
            val track = mutableListOf<Pair<Double, Double>>()
            while (frameNs <= end) {
                while (row < rows.size && rows[row][0].toLong() <= frameNs) {
                    val r = rows[row]
                    val ts = r[0].toLong()
                    val v = { n: Int -> FloatArray(n) { r[2 + it].toFloatOrNull() ?: 0f } }
                    when (r[1]) {
                        "game_rv" -> gate.rotation(ts, v(4))
                        "accel" -> gate.acceleration(ts, v(3))
                        "gyro" -> gate.gyroscope(ts, v(3))
                        "step" -> {
                            count++
                            motion.onStep(count * k, (frameNs - t0) / 1e9, k, ts)
                        }
                    }
                    row++
                }
                motion.setMoving(gate.moving(frameNs))
                track += (frameNs - t0) / 1e9 to motion.advance((frameNs - t0) / 1e9)
                frameNs += 16_666_667L
            }
            var episode = 0.0
            var worst = 0.0
            for ((a, b) in track.zipWithNext()) {
                val d = b.second - a.second
                episode = if (d < 0) episode - d else 0.0
                worst = maxOf(worst, episode)
            }
            // At each door the walker stopped to tap Mark, so the phone kept moving and the stop is
            // seen late; easing back up to ~1.5 steps there is the honest worst case.
            assertTrue(worst <= 1.6 * k, "$name eased back $worst px at once")
            assertTrue(
                maxFrameMove(track) < 2.5,
                "$name: largest frame move ${maxFrameMove(track)}"
            )
            assertTrue(
                abs(track.last().second - count * k) <= k,
                "$name ends at ${track.last().second}, tracker ${count * k}",
            )
        }
    }
}
