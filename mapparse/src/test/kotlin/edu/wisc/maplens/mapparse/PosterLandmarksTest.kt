package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosterLandmarksTest {
    @Test
    fun starSplitsEnrichedEdgeWithoutLosingDoor() {
        val graph = corridor()
        val warnings = mutableListOf<String>()
        val result = resolveStart(graph, listOf(PixelPoint(50.0, 5.0)), warnings)
        assertEquals(graph.landmarks, result.landmarks)
        assertEquals(NodeType.START, result.nodes[result.startNodeId]?.type)
        assertEquals(100.0, result.edges.sumOf { it.lengthPx })
        assertTrue(result.validate().isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun starCannotReplaceADoor() {
        val graph = corridor()
        val warnings = mutableListOf<String>()
        assertEquals(graph, resolveStart(graph, listOf(PixelPoint(0.0, 5.0)), warnings))
        assertTrue(warnings.single().contains("START"))
    }

    @Test
    fun starCannotSnapAcrossAnUnsupportedGap() {
        val graph = corridor()
        val warnings = mutableListOf<String>()
        val result = resolveStart(graph, listOf(PixelPoint(50.0, 150.0)), warnings)
        assertEquals(graph, result)
        assertTrue(warnings.single().contains("too far"))
    }

    @Test
    fun aStarBesideTheCorridorPutsStartAtTheStarWithALink() {
        // EH3 stair 3542: the star sits by the stair door, 94 px off the route paint.
        val warnings = mutableListOf<String>()
        val result = resolveStart(corridor(), listOf(PixelPoint(50.0, 94.0)), warnings)
        assertEquals(PixelPoint(50.0, 94.0), result.nodes.getValue(result.startNodeId).position)
        val link = result.edges.single { result.startNodeId in listOf(it.from, it.to) }
        assertEquals(94.0, link.lengthPx, 1e-9)
        assertEquals(PixelPoint(50.0, 0.0), result.nodes.getValue(link.from).position)
        assertTrue(result.validate().isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun missingOrAmbiguousStarsLeaveStartUnresolved() {
        val graph = corridor()
        val ambiguous = listOf(PixelPoint(50.0, 5.0), PixelPoint(80.0, 5.0))
        for (stars in listOf(emptyList(), ambiguous)) {
            val warnings = mutableListOf<String>()
            val result = resolveStart(graph, stars, warnings)
            assertEquals("", result.startNodeId)
            assertEquals(graph, result)
            assertTrue(warnings.single().contains("START"))
        }
    }

    private fun corridor(): FloorGraph {
        val a = Node("a", PixelPoint(0.0, 0.0), NodeType.DOOR)
        val b = Node("b", PixelPoint(100.0, 0.0), NodeType.DEAD_END)
        val edge = Edge("e1", a.id, b.id, listOf(a.position, b.position), 100.0)
        return FloorGraph(
            "engineering-hall",
            "3",
            mapOf(a.id to a, b.id to b),
            listOf(edge),
            listOf(Landmark("3418", a.id, 0.9f)),
            "",
        )
    }
}
