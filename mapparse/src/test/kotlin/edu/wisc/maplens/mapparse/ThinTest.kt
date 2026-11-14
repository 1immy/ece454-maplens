package edu.wisc.maplens.mapparse

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ThinTest {
    /** The original set-based thinning, kept here as the reference the fast one must match. */
    private fun referenceThin(pixels: ByteArray, stride: Int): Set<Int> {
        val offsets =
            intArrayOf(-stride - 1, -stride, -stride + 1, 1, stride + 1, stride, stride - 1, -1)
        val foreground = pixels.indices.filterTo(linkedSetOf()) { pixels[it] != 0.toByte() }
        val remove = mutableListOf<Int>()
        do {
            var changed = false
            for (phase in 1..2) {
                for (i in foreground) {
                    var mask = 0
                    for (bit in offsets.indices) {
                        if (pixels[i + offsets[bit]] != 0.toByte()) mask = mask or (1 shl bit)
                    }
                    val rule = thinningRules[mask]
                    if (rule == phase || rule == 3) remove += i
                }
                changed = changed || remove.isNotEmpty()
                for (i in remove) {
                    pixels[i] = 0
                    foreground.remove(i)
                }
                remove.clear()
            }
        } while (changed)
        return foreground
    }

    @Test
    fun `fast thinning gives exactly the reference skeleton`() {
        val random = Random(3)
        repeat(20) {
            val w = 120
            val h = 90
            val stride = w + 2
            val pixels = ByteArray(stride * (h + 2))
            // Random thick bars and blobs, like corridor paint, inside a one-pixel border.
            repeat(12) {
                val x0 = 1 + random.nextInt(w - 20)
                val y0 = 1 + random.nextInt(h - 20)
                val bw = 3 + random.nextInt(18)
                val bh = 3 + random.nextInt(18)
                for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) pixels[y * stride + x] = -1
            }
            val expected = referenceThin(pixels.copyOf(), stride)
            val actual = thin(pixels.copyOf(), stride)
            assertEquals(expected.toList(), actual.toList())
        }
    }
}
