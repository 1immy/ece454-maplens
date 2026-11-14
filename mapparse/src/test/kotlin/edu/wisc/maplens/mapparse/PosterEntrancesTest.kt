package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosterEntrancesTest {
    private val w = 800
    private val h = 800

    /** A 200 × 160 room with 3 px walls, top-left at (300, 300); [gaps] cut its walls. */
    private fun room(
        vararg gaps: IntRange,
        bottomGap: IntRange? = null,
        open: Boolean = false
    ): ByteArray {
        val walls = ByteArray(w * h)
        fun wall(x: Int, y: Int) {
            walls[y * w + x] = -1
        }
        for (t in 0 until 3) {
            for (x in 300..500) {
                if (gaps.none { x in it }) wall(x, 300 + t)
                if (bottomGap == null || x !in bottomGap) wall(x, 460 + t)
            }
            for (y in 300..462) {
                wall(300 + t, y)
                if (!open || y !in 340..440) wall(500 + t, y)
            }
        }
        return walls
    }

    @Test
    fun `a door gap in the wall is the entrance`() {
        val doors = assertNotNull(roomEntrances(PixelPoint(400.0, 380.0), room(380..409), w))
        assertEquals(1, doors.size)
        assertEquals(394.5, doors.single().x, 3.0)
        assertEquals(301.0, doors.single().y, 3.0)
    }

    @Test
    fun `a room with two doorways has two entrances`() {
        val doors =
            assertNotNull(
                roomEntrances(PixelPoint(400.0, 380.0), room(320..345, bottomGap = 450..475), w)
            )
        assertEquals(2, doors.size)
        assertTrue(doors.any { it.y < 310 } && doors.any { it.y > 455 })
    }

    @Test
    fun `a missing wall lets the paint out, so the room is left to the fallback`() {
        assertNull(roomEntrances(PixelPoint(400.0, 380.0), room(380..409, open = true), w))
    }
}
