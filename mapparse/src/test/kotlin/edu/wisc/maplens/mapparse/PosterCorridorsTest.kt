package edu.wisc.maplens.mapparse

import kotlin.test.Test
import kotlin.test.assertEquals

class PosterCorridorsTest {
    @Test
    fun thinningMatchesReferenceOnPaddedVerticalBar() {
        val mask = ByteArray(49)
        for (y in 1..5) for (x in 2..4) mask[y * 7 + x] = 1
        assertEquals(setOf(10, 17, 24), thin(mask, stride = 7))
    }
}
