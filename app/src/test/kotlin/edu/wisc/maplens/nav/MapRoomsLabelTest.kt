package edu.wisc.maplens.nav

import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapRoomsLabelTest {
    private fun word(text: String, left: Int, right: Int) =
        MapRooms.Word(text, left, 100, right, 130)

    @Test
    fun `a suffix letter read apart joins its room number`() {
        // ML Kit can return "3540B" as "3540" and "B": that is room 3540B, not room 3540.
        val joined = MapRooms.joinSuffixes(listOf(word("3540", 10, 70), word("B", 74, 88)))
        assertEquals(listOf("3540B"), joined.map { it.text })
        assertEquals(10, joined.single().left)
        assertEquals(88, joined.single().right)
        assertTrue(joined.single().joined)
    }

    @Test
    fun `a far letter or a second number stays separate`() {
        val far = MapRooms.joinSuffixes(listOf(word("3540", 10, 70), word("B", 200, 214)))
        assertEquals(listOf("3540", "B"), far.map { it.text })
        val two = MapRooms.joinSuffixes(listOf(word("3434", 10, 70), word("3436", 74, 134)))
        assertEquals(listOf("3434", "3436"), two.map { it.text })
    }

    @Test
    fun `the read nearer the room's door wins`() {
        val door = listOf(PixelPoint(807.0, 780.0))
        val own = LabelBox("3540", 636.0, 775.0, 722.0, 819.0)
        val inside3540B = LabelBox("3540", 624.0, 865.0, 700.0, 911.0)
        assertTrue(MapRooms.closer(own, inside3540B, door))
        assertFalse(MapRooms.closer(inside3540B, own, door))
        // Without a door to compare against, the first read stays.
        assertFalse(MapRooms.closer(own, inside3540B, emptyList()))
    }
}
