package edu.wisc.maplens.nav

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceSearchTest {
    /** START at x=0; rooms along one corridor at x = 10..60; an exit and an elevator. */
    private val graph = run {
        val doors =
            listOf("3418" to 10.0, "3540" to 20.0, "3541" to 30.0, "1341" to 40.0, "3418" to 50.0)
        val nodes = buildList {
            add(Node("S", PixelPoint(0.0, 0.0), NodeType.START))
            doors.forEachIndexed { i, (_, x) ->
                add(
                    Node("D$i", PixelPoint(x, 0.0), NodeType.DOOR)
                )
            }
            add(Node("X", PixelPoint(60.0, 0.0), NodeType.EXIT))
            add(Node("L", PixelPoint(-10.0, 0.0), NodeType.ELEVATOR))
        }
        val order = listOf("L", "S") + doors.indices.map { "D$it" } + "X"
        val edges = order.zipWithNext { a, b ->
            val pa = nodes.first { it.id == a }.position
            val pb = nodes.first { it.id == b }.position
            Edge("$a-$b", a, b, listOf(pa, pb), pa.distanceTo(pb))
        }
        FloorGraph(
            buildingId = "test",
            floor = "3",
            nodes = nodes.associateBy { it.id },
            edges = edges,
            landmarks = doors.mapIndexed { i, (label, _) -> Landmark(label, "D$i", 1f) },
            startNodeId = "S",
        )
    }
    private val places = graph.places()

    /** Straight-line distance from START stands in for route length on this line graph. */
    private val search = PlaceSearch(places) { it.position.distanceTo(PixelPoint(0.0, 0.0)) }

    @Test
    fun `rooms are listed once each, with every door, then features`() {
        val rooms = places.filter { it.kind == PlaceKind.ROOM }
        assertEquals(listOf("1341", "3418", "3540", "3541"), rooms.map { it.label })
        assertEquals(listOf("D0", "D4"), rooms.first { it.label == "3418" }.nodeIds)
        assertEquals(
            setOf(PlaceKind.EXIT, PlaceKind.ELEVATOR),
            places.map {
                it.kind
            }.toSet() - PlaceKind.ROOM
        )
    }

    @Test
    fun `prefix matches come before contains matches, closer first`() {
        val hits = search.search("34")
        assertEquals(listOf("3418", "1341"), hits.map { it.place.label })
        assertEquals(0..1, hits[0].matched)
        assertEquals(1..2, hits[1].matched)
    }

    @Test
    fun `within a rank closer rooms come first`() {
        assertEquals(
            listOf("3418", "3540", "3541", "1341"),
            search.search("3").map { it.place.label }
        )
    }

    @Test
    fun `room prefix and lower case are ignored`() {
        assertEquals("3540", search.search("Room 3540").single().place.label)
        assertEquals("3540", search.search("rm3540").single().place.label)
    }

    @Test
    fun `feature words find exits and elevators`() {
        assertEquals(PlaceKind.EXIT, search.search("ex").single().place.kind)
        assertEquals(PlaceKind.ELEVATOR, search.search("lift").single().place.kind)
        assertNull(search.search("ex").single().matched)
    }

    @Test
    fun `blank or unknown queries find nothing`() {
        assertTrue(search.search("  ").isEmpty())
        assertTrue(search.search("9999").isEmpty())
    }

    @Test
    fun `nearest picks the closest place of a kind`() {
        assertEquals("exit:X", search.nearest(PlaceKind.EXIT)?.key)
        assertNull(search.nearest(PlaceKind.AED))
    }

    @Test
    fun `nearest room label describes a feature's location`() {
        assertEquals("3418", graph.nearestRoomLabel(PixelPoint(52.0, 0.0)))
    }
}
