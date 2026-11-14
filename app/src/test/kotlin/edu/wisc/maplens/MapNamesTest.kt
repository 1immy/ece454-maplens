package edu.wisc.maplens

import kotlin.test.Test
import kotlin.test.assertEquals

class MapNamesTest {
    @Test
    fun `floors get English ordinals`() {
        assertEquals("1st floor", ordinalFloor(1))
        assertEquals("2nd floor", ordinalFloor(2))
        assertEquals("3rd floor", ordinalFloor(3))
        assertEquals("4th floor", ordinalFloor(4))
        assertEquals("11th floor", ordinalFloor(11))
        assertEquals("12th floor", ordinalFloor(12))
        assertEquals("21st floor", ordinalFloor(21))
    }
}
