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

    @Test
    fun `walls flood last, so a region does not leak through them`() {
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
        }
    }
}
