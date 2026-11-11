package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignTextTest {
    private val graph = FloorGraph(
        "t",
        "1",
        listOf(
            Node("S", PixelPoint(0.0, 0.0), NodeType.START),
            Node("A", PixelPoint(1.0, 0.0), NodeType.DOOR),
            Node("A2", PixelPoint(2.0, 0.0), NodeType.DOOR),
            Node("B", PixelPoint(3.0, 0.0), NodeType.DOOR),
            Node("X", PixelPoint(4.0, 0.0), NodeType.EXIT),
        ).associateBy { it.id },
        emptyList(),
        listOf(
            Landmark("3546", "A", 1f),
            Landmark("3546", "A2", 1f),
            Landmark("3539A", "B", 1f),
            Landmark("3540", "X", 1f),
        ),
        "S",
    )

    @Test
    fun `normalize fixes the usual misreads but keeps a letter suffix`() {
        assertEquals("3418", SignText.normalize(" 34l8 "))
        assertEquals("3405", SignText.normalize("34O5"))
        assertEquals("3415", SignText.normalize("341S"))
        assertEquals("3539A", SignText.normalize("3539a"))
        assertTrue(SignText.isRoomNumber("Room 3418".removePrefix("Room ")))
        assertFalse(SignText.isRoomNumber("EXIT"))
    }

    @Test
    fun `a room with several doors gives every door`() {
        assertEquals("3546" to listOf("A", "A2"), SignText.doorsFor("3546", graph))
        assertEquals("3539A" to listOf("B"), SignText.doorsFor("3539A", graph))
    }

    @Test
    fun `one edit away matches only when it is the only candidate`() {
        assertEquals("3546", SignText.doorsFor("3548", graph)?.first)
        // 3540 is on an exit, not a door, so it doesn't compete.
        assertNull(SignText.doorsFor("9999", graph))
        assertNull(SignText.doorsFor("EXIT", graph))
    }

    @Test
    fun `distance from character height follows the pinhole model`() {
        // 4 cm characters, 1000 px focal length, 20 px tall in the picture: 2 m away.
        assertEquals(2.0, SignText.distanceMeters(20.0, 1000.0, 0.04)!!, 1e-9)
        assertNull(SignText.distanceMeters(0.0, 1000.0, 0.04))
    }
}
