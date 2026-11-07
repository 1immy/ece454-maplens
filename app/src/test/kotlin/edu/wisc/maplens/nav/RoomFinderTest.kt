package edu.wisc.maplens.nav

import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomFinderTest {
    /**
     * 60 × 30 cells, 1 px each. Two rooms on top (A: x 1..28, B: x 31..58, y 1..14) above a
     * corridor (y 16..28). Each room has a 2-cell doorway in its bottom wall at y = 15.
     *
     *   ##############################################################
     *   #     A              #     B                                  #
     *   ######  ##################  ###################################
     *   #                corridor                                     #
     */
    private val grid: WallGrid = run {
        val w = 60
        val h = 30
        val luma = IntArray(w * h) { 255 }
        fun wall(x: Int, y: Int) {
            luma[y * w + x] = 0
        }
        for (x in 0 until w) {
            wall(x, 0)
            wall(x, h - 1)
            if (x !in 10..11 && x !in 40..41) wall(x, 15)
        }
        for (y in 0 until h) {
            wall(0, y)
            wall(w - 1, y)
        }
        for (y in 0..15) {
            wall(29, y)
            wall(30, y)
        }
        // The printed number of room B, dark ink in the middle of the room.
        for (x in 44..47) for (y in 6..8) wall(x, y)
        WallGrid.fromLuminance(luma, w, h, scale = 1, closeRadii = listOf(0, 1))
    }

    private val doors = mapOf(
        "A" to listOf(PixelPoint(10.5, 20.0)),
        "B" to listOf(PixelPoint(40.5, 20.0)),
    )

    private val finder = RoomFinder(grid, emptyList(), doors, maxRoomShare = 0.3)

    @Test
    fun `dilation thickens walls by the radius`() {
        val src = BooleanArray(9) { it == 4 }
        val out = WallGrid.dilate(src, 3, 3, 1)
        assertTrue(out.all { it })
        assertEquals(src.toList(), WallGrid.dilate(src, 3, 3, 0).toList())
    }

    @Test
    fun `a tap inside a room finds its door when no number was read`() {
        assertEquals("A", finder.roomAt(PixelPoint(5.0, 5.0), doorRadiusPx = 3.0)?.label)
        assertEquals("B", finder.roomAt(PixelPoint(55.0, 5.0), doorRadiusPx = 3.0)?.label)
    }

    @Test
    fun `the doorway leaks at the first level but the next level closes it`() {
        val area = assertNotNull(finder.areaAround(PixelPoint(5.0, 5.0)))
        assertTrue(PixelPoint(20.0, 10.0) in area)
        assertFalse(PixelPoint(20.0, 20.0) in area, "the corridor must not be part of room A")
    }

    @Test
    fun `a tap on printed ink starts from the nearest open cell`() {
        assertEquals("B", finder.roomAt(PixelPoint(45.5, 7.5), doorRadiusPx = 3.0)?.label)
    }

    @Test
    fun `a read number inside the area beats the doors`() {
        val withLabels = RoomFinder(
            grid,
            listOf(LabelBox("3418", 44.0, 6.0, 48.0, 9.0)),
            doors,
            maxRoomShare = 0.3,
        )
        assertEquals("3418", withLabels.roomAt(PixelPoint(55.0, 12.0), 3.0)?.label)
        assertEquals("3418", withLabels.roomAt(PixelPoint(46.0, 7.0), 3.0)?.label)
        assertEquals("A", withLabels.roomAt(PixelPoint(5.0, 5.0), 3.0)?.label)
    }

    @Test
    fun `otsu splits dark ink from light paper`() {
        val luma = IntArray(100) { if (it < 30) 40 + it % 5 else 210 + it % 7 }
        val t = WallGrid.otsu(luma)
        assertTrue(t in 45..210, "threshold $t should fall between ink and paper")
    }

    @Test
    fun `a fill reaching two room numbers counts as a leak`() {
        // No size cap here: only the second number in the corridor shows that A leaked.
        val leaky = RoomFinder(
            grid,
            listOf(LabelBox("A1", 4.0, 4.0, 6.0, 5.0), LabelBox("C1", 20.0, 22.0, 22.0, 23.0)),
            doors,
            maxRoomShare = 0.95,
        )
        val area = assertNotNull(leaky.areaAround(PixelPoint(5.0, 10.0)))
        assertFalse(PixelPoint(20.0, 20.0) in area, "should retry with the doorway closed")
        assertEquals("A1", leaky.roomAt(PixelPoint(20.0, 10.0), 3.0)?.label)
    }

    @Test
    fun `a thin tail through a gap is cut off the room`() {
        // A 20 × 14 room with a 2-cell-wide, 12-cell-long passage leaving its right side.
        val w = 40
        val h = 18
        val luma = IntArray(w * h) { 0 }
        fun open(x: Int, y: Int) {
            luma[y * w + x] = 255
        }
        for (y in 2..15) for (x in 2..21) open(x, y)
        for (x in 22..33) for (y in 8..9) open(x, y)
        val grid = WallGrid.fromLuminance(luma, w, h, scale = 1, closeRadii = listOf(0))
        val area =
            assertNotNull(
                RoomFinder(
                    grid,
                    emptyList(),
                    emptyMap(),
                    maxRoomShare = 0.9
                ).areaAround(PixelPoint(10.0, 10.0))
            )
        assertTrue(PixelPoint(3.0, 3.0) in area, "room corners stay")
        assertTrue(PixelPoint(20.0, 14.0) in area, "room corners stay")
        assertFalse(PixelPoint(30.0, 8.0) in area, "the passage is cut off")
    }

    @Test
    fun `rooms that always leak into each other are split between their numbers`() {
        // Three offices in a row, open at the bottom onto one shared strip: no wall level
        // separates them, so the split between the numbers must.
        val w = 34
        val h = 16
        val luma = IntArray(w * h) { 0 }
        for (y in 2..13) for (x in 2..31) luma[y * w + x] = 255
        for (x in listOf(11, 21)) for (y in 2..9) luma[y * w + x] = 0
        val grid = WallGrid.fromLuminance(luma, w, h, scale = 1, closeRadii = listOf(0, 1))
        val labels = listOf(
            LabelBox("R1", 5.0, 4.0, 7.0, 5.0),
            LabelBox("R2", 15.0, 4.0, 17.0, 5.0),
            LabelBox("R3", 25.0, 4.0, 27.0, 5.0),
        )
        val finder = RoomFinder(grid, labels, emptyMap(), maxRoomShare = 0.95, minRoomShare = 0.0)
        val hit = assertNotNull(finder.roomAt(PixelPoint(16.0, 7.0), 1.0))
        assertEquals("R2", hit.label)
        val area = assertNotNull(hit.area)
        assertTrue(PixelPoint(16.0, 3.0) in area)
        assertFalse(PixelPoint(6.0, 3.0) in area, "the office to the left is not R2")
        assertFalse(PixelPoint(26.0, 3.0) in area, "the office to the right is not R2")
    }

    @Test
    fun `in the corridor only a nearby door counts`() {
        val small = RoomFinder(grid, emptyList(), doors, maxRoomShare = 0.05)
        assertEquals("B", small.roomAt(PixelPoint(42.0, 22.0), doorRadiusPx = 5.0)?.label)
        assertNull(small.roomAt(PixelPoint(25.0, 25.0), doorRadiusPx = 5.0))
    }
}
