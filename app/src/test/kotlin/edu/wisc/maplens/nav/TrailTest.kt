package edu.wisc.maplens.nav

import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class TrailTest {
    @Test
    fun `the trail follows the walked points, turns and U-turns included`() {
        val trail = Trail(PixelPoint(0.0, 0.0))
        trail.add(PixelPoint(10.0, 0.0))
        trail.add(PixelPoint(10.0, 10.0))
        assertEquals(30.0, trail.add(PixelPoint(10.0, 0.0)), 1e-9)
        assertEquals(PixelPoint(5.0, 0.0), trail.pointAt(5.0))
        assertEquals(PixelPoint(10.0, 5.0), trail.pointAt(15.0))
        assertEquals(PixelPoint(10.0, 5.0), trail.pointAt(25.0))
        assertEquals(PixelPoint(10.0, 0.0), trail.pointAt(99.0))
        assertEquals(PixelPoint(0.0, 0.0), trail.pointAt(-1.0))
    }

    @Test
    fun `a repeated position adds nothing`() {
        val trail = Trail(PixelPoint(0.0, 0.0))
        trail.add(PixelPoint(0.1, 0.0))
        assertEquals(0.0, trail.length, 1e-9)
    }
}
