package edu.wisc.maplens.mapparse

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosterCutNumbersTest {
    @Test
    fun `a reread that adds the crossed digit is accepted`() {
        // EH3: a wall corner through the 0 of 3540 reads "354".
        assertTrue(keepsDigits("354", "3540", '3'))
        // The crossed digit can be anywhere in the number.
        assertTrue(keepsDigits("340", "3540", '3'))
        assertTrue(keepsDigits("354", "3540B", '3'))
    }

    @Test
    fun `a reread that changes digits, floor or length is not`() {
        assertFalse(keepsDigits("354", "3640", '3'))
        assertFalse(keepsDigits("354", "2354", '3'))
        assertFalse(keepsDigits("354", "354", '3'))
        assertFalse(keepsDigits("354", "35400", '3'))
    }
}
