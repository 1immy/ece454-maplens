package edu.wisc.maplens.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomBoundsTest {
    /**
     * 50 × 24 cells, 1 px each. Room A (x 1..19) and room B (x 21..48) above a corridor
     * (y 12..22); they share a wall at x = 20 with a doorway at y 4..6, and each opens onto
     * the corridor through a doorway in the wall at y = 11. Room A holds a stray line of
     * "furniture" that must not stop its box.
     */
    private val grid: WallGrid = run {
        val w = 50
        val h = 24
        val luma = IntArray(w * h) { 255 }
        fun ink(x: Int, y: Int) {
            luma[y * w + x] = 0
        }
        for (x in 0 until w) {
            ink(x, 0)
            ink(x, h - 1)
            if (x !in 8..10 && x !in 30..32) ink(x, 11)
        }
        for (y in 0 until h) {
            ink(0, y)
            ink(w - 1, y)
        }
        for (y in 0..11) if (y !in 4..6) ink(20, y)
        for (x in 4..7) ink(x, 8)
        WallGrid.fromLuminance(luma, w, h, scale = 1, closeRadii = listOf(0))
    }

    private val labels = listOf(
        LabelBox("A", 9.0, 3.0, 12.0, 4.0),
        LabelBox("B", 33.0, 5.0, 36.0, 6.0),
    )

    @Test
    fun `boxes grow wall to wall through text and stop at the shared wall`() {
        val rooms = RoomBounds.grow(grid, labels, maxShare = 0.5)
        val a = rooms.getValue("A")
        val b = rooms.getValue("B")
        assertEquals(RoomBox(1.0, 1.0, 20.0, 11.0), a)
        assertEquals(RoomBox(21.0, 1.0, 49.0, 11.0), b)
    }

    @Test
    fun `neighbours never overlap even across a doorway`() {
        val rooms = RoomBounds.grow(grid, labels, maxShare = 0.5)
        val a = rooms.getValue("A")
        val b = rooms.getValue("B")
        assertTrue(a.right <= b.left, "A ends at ${a.right}, B starts at ${b.left}")
    }

    @Test
    fun `a box in open space runs away and gets no outline`() {
        val open = WallGrid.fromLuminance(
            IntArray(40 * 40) { 255 },
            40,
            40,
            1,
            closeRadii = listOf(0)
        )
        val rooms = RoomBounds.grow(
            open,
            listOf(LabelBox("X", 18.0, 18.0, 20.0, 19.0)),
            maxShare = 0.1
        )
        assertNull(rooms["X"])
    }
}
