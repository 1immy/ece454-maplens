package edu.wisc.maplens.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapRoomsSplitTest {
    private val rooms =
        listOf("3434", "3436", "3438", "3440", "3442", "3419", "3421", "3423", "3539A")

    @Test
    fun `a run of neighbouring numbers is cut back into rooms`() {
        val parts = MapRooms.splitRun("343834363434", rooms)
        assertEquals(listOf("3438", "3436", "3434"), parts.map { it.first })
        assertEquals(listOf(0 to 4, 4 to 8, 8 to 12), parts.map { it.second to it.third })
    }

    @Test
    fun `misread characters and a cut-off tail are skipped`() {
        assertEquals(
            listOf("3423", "3421"),
            MapRooms.splitRun("34233421S419", rooms).map { it.first }
        )
        assertEquals(
            listOf("3442", "3440"),
            MapRooms.splitRun("3442344034", rooms).map { it.first }
        )
    }

    @Test
    fun `a single number or short text is never split`() {
        assertTrue(MapRooms.splitRun("3539A", rooms).isEmpty())
        assertTrue(MapRooms.splitRun("EXIT", rooms).isEmpty())
    }
}
