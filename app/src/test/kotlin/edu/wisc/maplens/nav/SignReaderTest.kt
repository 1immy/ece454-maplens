package edu.wisc.maplens.nav

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignReaderTest {
    private val graph = FloorGraph(
        "t",
        "1",
        listOf(
            Node("S", PixelPoint(0.0, 0.0), NodeType.START),
            Node("D1", PixelPoint(100.0, 0.0), NodeType.DOOR),
            Node("D2", PixelPoint(200.0, 0.0), NodeType.DOOR),
        ).associateBy { it.id },
        emptyList(),
        listOf(Landmark("3418", "D1", 1f), Landmark("3546", "D2", 1f)),
        "S",
    )

    /** 1000 px wide, 90° field of view: focal length 500 px. */
    private val reader = SignReader(graph, textHeightMeters = 0.04)
    private val hfov = PI / 2

    @Test
    fun `a centred sign lies along the camera heading at the pinhole distance`() {
        // 20 px tall characters at f = 500 px: 0.04 * 500 / 20 = 1 m.
        val seen = reader.read(listOf(TextLine("3418", 480f, 300f, 520f, 320f)), 1000, hfov, 1.0)
        val d = seen.single()
        assertEquals("3418", d.label)
        assertEquals(listOf("D1"), d.doors)
        assertEquals(1.0, d.bearingRad, 1e-9)
        assertEquals(1.0, d.distanceMeters!!, 1e-9)
    }

    @Test
    fun `text right of centre is clockwise of the heading`() {
        // Centre at x = 1000: 500 px right of centre at f = 500 is 45°.
        val d = reader.read(listOf(TextLine("3418", 980f, 0f, 1020f, 10f)), 1000, hfov, 0.0)
        assertEquals(PI / 4, d.single().bearingRad, 1e-9)
    }

    @Test
    fun `only room numbers are kept, and unknown rooms have no doors`() {
        val seen = reader.read(
            listOf(
                TextLine("EXIT", 0f, 0f, 10f, 10f),
                TextLine("Room", 0f, 0f, 10f, 10f),
                TextLine("9999", 0f, 0f, 10f, 10f),
                TextLine("354G", 0f, 0f, 10f, 10f),
            ),
            1000,
            hfov,
            0.0,
        )
        // 354G is one edit from 3546 (G for 6) and from no other room, so it reads as 3546.
        assertEquals(listOf("9999", "3546"), seen.map { it.label })
        assertTrue(seen[0].doors.isEmpty())
        assertEquals(listOf("D2"), seen[1].doors)
    }

    @Test
    fun `a room counts once it is read in three of the last five frames`() {
        val voter = SignVoter()
        fun frame(vararg labels: String, x: Float = 500f, h: Float = 20f) = reader.read(
            labels.map { TextLine(it, x - 20, 300f, x + 20, 300f + h) },
            1000,
            hfov,
            0.0,
        )
        voter.add(frame("3418"))
        voter.add(frame())
        voter.add(frame("9999"))
        voter.add(frame("3418", h = 25f))
        assertNull(voter.stable())
        voter.add(frame("3418", h = 16f))
        val reads = voter.stable()!!
        assertEquals(3, reads.size)
        val sighting = SignVoter.sighting(reads, 7L, pxPerMeter = 25.0)
        assertEquals("3418", sighting.observation.text)
        assertEquals("D1", sighting.observation.matchedNodeId)
        // Median of 1.0, 0.8 and 1.25 m is 1.0 m = 25 px.
        assertEquals(25.0, sighting.distancePx!!, 1e-9)
    }
}
