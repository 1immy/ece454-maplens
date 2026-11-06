package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosterRoomsTest {
    @Test
    fun roomNormalizationPreservesSuffixAndRejectsAmbiguousDigits() {
        assertEquals("3418A", normalizeRoom(" (34-18 a) "))
        assertNull(normalizeRoom("34I8"))
        assertNull(normalizeRoom("3418 3422"))
    }

    @Test
    fun wallClearCorridorWinsOverCloserBlockedCorridor() {
        val width = 60
        val walls = ByteArray(width * 60)
        for (y in 0 until 60) for (x in 25..33) walls[y * width + x] = 1
        val blocked = PixelPoint(36.0, 30.0)
        val clear = PixelPoint(4.0, 30.0)
        assertEquals(
            clear,
            corridorPath(PixelPoint(24.0, 30.0), listOf(blocked, clear), walls, width)?.target,
        )
    }

    @Test
    fun roomRowsIgnorePrintedDividersAndRejectProse() {
        assertEquals(listOf("3425", "3423", "3421", "3419"), roomRowTokens("3425 3423|3421 3419"))
        assertEquals(listOf("3611", "3613"), roomRowTokens(",36113613"))
        assertTrue(roomRowTokens("AED ROOM 3421 | 3617").isEmpty())
        assertTrue(roomRowTokens("3421, 3617").isEmpty())
        assertEquals(listOf("3425", "3419"), standaloneRoomTokens("3425 3423S421 3419"))
        assertTrue(standaloneRoomTokens("AED ROOM 3421 3617").isEmpty())
        assertTrue(standaloneRoomTokens("3040 B222 B335").isEmpty())
    }
}
