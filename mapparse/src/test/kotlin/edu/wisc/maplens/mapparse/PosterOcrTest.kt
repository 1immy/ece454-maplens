package edu.wisc.maplens.mapparse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.opencv.core.Point

class PosterOcrTest {
    @Test
    fun ctcPreservesRepeatedDigitsAndSuffix() {
        val alphabet = listOf("", "3", "5", "6", "A")
        val ids = listOf(1, 1, 2, 0, 2, 3, 4, 4)
        val scores = FloatArray(ids.size * alphabet.size)
        for ((column, symbol) in ids.withIndex()) scores[column * alphabet.size + symbol] = 0.95f
        val decoded = decodePosterText(scores, alphabet)
        assertEquals("3556A", decoded.joinToString("") { it.text })
        assertEquals(listOf(0, 2, 4, 5, 6), decoded.map { it.column })
    }

    @Test
    fun marginVoteNeedsTwoAgreeingRoomReads() {
        assertEquals("3613", marginVote(listOf("3613", "36513", "3613")))
        assertEquals("3540", marginVote(listOf("354p", "35-40", "3540")))
        assertNull(marginVote(listOf("3613", "3513", "3713")))
        assertNull(marginVote(listOf("2613", "2613", "AED")))
    }

    @Test
    fun onlyNearlyIdenticalBoxesCountAsTheSameWord() {
        val word = box(100.0, 100.0, 170.0, 140.0)
        assertTrue(sameWord(word, box(102.0, 101.0, 172.0, 141.0)))
        assertFalse(sameWord(box(40.0, 100.0, 400.0, 140.0), word))
        assertFalse(sameWord(word, box(300.0, 100.0, 370.0, 140.0)))
    }

    private fun box(left: Double, top: Double, right: Double, bottom: Double) = arrayOf(
        Point(left, top),
        Point(right, top),
        Point(right, bottom),
        Point(left, bottom),
    )

    @Test
    fun detectorTilesCoverTheImageWithOverlap() {
        assertEquals(listOf(0, 848), posterTileOrigins(2000, 1152))
        assertEquals(listOf(0, 605), posterTileOrigins(1469, 864))
        assertEquals(listOf(0), posterTileOrigins(128, 128))
    }
}
