package edu.wisc.maplens.mapparse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriorityFloodTest {
    /**
     * Two open areas joined by a narrow neck (a doorway), relief high at the neck as the
     * distance-to-wall relief is. Each side's seed must keep its own side, meeting at the neck.
     */
    @Test
    fun `regions meet at the narrowest place between them`() {
        val w = 30
        val h = 10
        val relief = ByteArray(w * h) { i ->
            val x = i % w
            (if (x in 14..15) 200 else 10).toByte()
        }
        val seeds = IntArray(w * h)
        seeds[5 * w + 2] = 3 // a room seed on the left
        seeds[5 * w + 27] = 2 // the corridor on the right
        val region = priorityFlood(seeds, relief, w, h)
        for (y in 0 until h) {
            for (x in 0 until 13) assertEquals(3, region[y * w + x], "left ($x,$y)")
            for (x in 17 until w) assertEquals(2, region[y * w + x], "right ($x,$y)")
        }
        val neck = (0 until h).flatMap { y -> listOf(region[y * w + 14], region[y * w + 15]) }
        assertTrue(neck.all { it == 2 || it == 3 || it == -1 })
        assertTrue(neck.contains(2) || neck.contains(-1))
    }

    /**
     * The parser seeds the whole border, which takes the flood's fast loop. It must label
     * every pixel as the general loop does: here the general loop is kept as written before
     * the fast one existed, and both run on random reliefs with walls, seeds and a border.
     */
    @Test
    fun `a framed image floods exactly as the general loop does`() {
        val random = java.util.Random(454)
        var meetings = 0
        repeat(200) {
            val w = 8 + random.nextInt(40)
            val h = 8 + random.nextInt(40)
            val levels = intArrayOf(4, 4, 60, 120, 200, 254, 255)
            val relief = ByteArray(w * h) { levels[random.nextInt(levels.size)].toByte() }
            val seeds = IntArray(w * h)
            for (x in 0 until w) {
                seeds[x] = 1
                seeds[(h - 1) * w + x] = 1
            }
            for (y in 0 until h) {
                seeds[y * w] = 1
                seeds[y * w + w - 1] = 1
            }
            repeat(1 + random.nextInt(6)) { k ->
                seeds[(1 + random.nextInt(h - 2)) * w + 1 + random.nextInt(w - 2)] = 2 + k
            }
            val expected = referenceFlood(seeds, relief, w, h)
            val flooded = priorityFlood(seeds, relief, w, h)
            assertTrue(expected.contentEquals(flooded), "poster $it, $w x $h")
            meetings += flooded.count { it == -1 }
        }
        assertTrue(meetings > 200, "regions met at only $meetings pixels")
    }

    /** [priorityFlood]'s general loop, as it was when it was the only one. */
    private fun referenceFlood(
        seeds: IntArray,
        relief: ByteArray,
        width: Int,
        height: Int
    ): IntArray {
        val n = seeds.size
        val label = seeds.copyOf()
        val queued = BooleanArray(n)
        val buckets = Array(255) { mutableListOf<Int>() }

        fun neighbours(i: Int): List<Int> {
            val x = i % width
            return buildList {
                if (x > 0) add(i - 1)
                if (x < width - 1) add(i + 1)
                if (i >= width) add(i - width)
                if (i < n - width) add(i + width)
            }
        }

        fun queue(j: Int, level: Int) {
            val r = relief[j].toInt() and 0xFF
            if (r >= 255 || queued[j] || label[j] != 0) return
            queued[j] = true
            buckets[maxOf(r, level)].add(j)
        }
        for (i in 0 until n) {
            if (label[i] > 0) neighbours(i).forEach { queue(it, 0) }
        }
        for (lv in 0 until 255) {
            val bucket = buckets[lv]
            var k = 0
            while (k < bucket.size) {
                val i = bucket[k++]
                val near = neighbours(i)
                var found = 0
                var clash = false
                for (j in near) {
                    val l = label[j]
                    if (l > 0) {
                        if (found == 0) found = l else if (found != l) clash = true
                    }
                }
                if (clash) {
                    label[i] = -1
                    continue
                }
                if (found == 0) continue
                label[i] = found
                near.forEach { queue(it, lv) }
            }
        }
        check(height * width == n)
        return label
    }

    @Test
    fun `walls are never flooded, so a region does not leak through them`() {
        val w = 20
        val h = 5
        // A wall column at x = 10 (relief 255) with open floor on both sides.
        val relief = ByteArray(w * h) { i -> (if (i % w == 10) 255 else 0).toByte() }
        val seeds = IntArray(w * h)
        seeds[2 * w + 1] = 3
        seeds[2 * w + 18] = 2
        val region = priorityFlood(seeds, relief, w, h)
        for (y in 0 until h) {
            for (x in 0 until 10) assertEquals(3, region[y * w + x])
            for (x in 11 until w) assertEquals(2, region[y * w + x])
            assertEquals(0, region[y * w + 10])
        }
    }
}
