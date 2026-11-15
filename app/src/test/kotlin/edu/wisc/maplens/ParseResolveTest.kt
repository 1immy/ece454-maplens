package edu.wisc.maplens

import edu.wisc.maplens.mapparse.ParseProgress
import edu.wisc.maplens.mapparse.ParseStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParseResolveTest {
    @Test
    fun frostIsFullAtTheStartAndGoneAtTheEnd() {
        assertEquals(1f, frostFor(0f))
        assertEquals(0f, frostFor(1f))
        assertEquals(0f, frostFor(1.5f))
        assertTrue(frostFor(0.5f) < 0.5f, "frost thins faster than the bar fills")
    }

    @Test
    fun frostOnlyThinsAsTheParseAdvances() {
        val steps = (0..20).map { frostFor(it / 20f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun noLabelsBeforeRoomsAreRead() {
        assertEquals(0, labelsShown(ParseProgress(ParseStage.STRAIGHTEN), 6))
        assertEquals(0, labelsShown(ParseProgress(ParseStage.FIND_TEXT), 6))
        assertEquals(0, labelsShown(ParseProgress(ParseStage.READ_ROOMS), 6))
    }

    @Test
    fun labelsFollowTheRoomsRead() {
        assertEquals(3, labelsShown(ParseProgress(ParseStage.READ_ROOMS, 20, 40), 6))
        assertEquals(6, labelsShown(ParseProgress(ParseStage.READ_ROOMS, 40, 40), 6))
        assertEquals(6, labelsShown(ParseProgress(ParseStage.READ_ROOMS, 50, 40), 6))
    }

    @Test
    fun allLabelsOnceReadingIsDone() {
        assertEquals(6, labelsShown(ParseProgress(ParseStage.RECHECK_SMALL), 6))
        assertEquals(6, labelsShown(ParseProgress(ParseStage.PLACE_ROOMS), 6))
    }
}
