package edu.wisc.maplens.routing

import edu.wisc.maplens.model.Edge
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.model.Landmark
import edu.wisc.maplens.model.Node
import edu.wisc.maplens.model.NodeType
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.Route
import java.io.File
import java.util.PriorityQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelRoutingTest {
    private val router = AStarRouter()

    /**
     * A corridor S - A - B - C - D running right, plus an island X that nothing reaches.
     * Room "3546" has doors at A (100 px from S) and D (400 px); room "3650" only at C.
     * "3999" sits only on the island.
     */
    private val graph = run {
        val xs = mapOf("S" to 0.0, "A" to 100.0, "B" to 200.0, "C" to 300.0, "D" to 400.0)
        val nodes = xs.map { (id, x) ->
            Node(id, PixelPoint(x, 0.0), if (id == "S") NodeType.START else NodeType.DOOR)
        } + Node("X", PixelPoint(0.0, 500.0), NodeType.DOOR)
        val byId = nodes.associateBy { it.id }
        fun e(a: String, b: String): Edge {
            val pa = byId.getValue(a).position
            val pb = byId.getValue(b).position
            return Edge("$a$b", a, b, listOf(pa, pb), pa.distanceTo(pb))
        }
        FloorGraph(
            buildingId = "t",
            floor = "1",
            nodes = byId,
            edges = listOf(e("S", "A"), e("A", "B"), e("B", "C"), e("C", "D")),
            landmarks = listOf(
                Landmark("3546", "D", 1f),
                Landmark("3546", "A", 1f),
                Landmark("3650", "C", 1f),
                Landmark("3999", "X", 1f),
                Landmark("OFFMAP", "B", 1f),
            ),
            startNodeId = "S",
        )
    }

    @Test
    fun `label routes to the nearest of several doors`() {
        val route = assertNotNull(router.routeToLabel(graph, "S", "3546"))
        assertEquals(listOf("S", "A"), route.nodeIds)
        assertEquals(100.0, route.lengthPx, 1e-9)
        // From the far end, the other entrance is nearer.
        assertEquals(listOf("D"), assertNotNull(router.routeToLabel(graph, "D", "3546")).nodeIds)
        assertEquals("A", router.routeToLabel(graph, "B", "3546")!!.nodeIds.last())
    }

    @Test
    fun `labels are matched after normalizing case, spaces and punctuation`() {
        assertEquals("C", router.routeToLabel(graph, "S", " 36.50 ")!!.nodeIds.last())
        assertEquals("3539A", normalizeLabel("3539a"))
        assertEquals("TO-EXIT", normalizeLabel("to-exit"))
    }

    @Test
    fun `unknown or unreachable label gives no route`() {
        assertNull(router.routeToLabel(graph, "S", "1234"))
        assertNull(router.routeToLabel(graph, "S", ""))
        assertNull(router.routeToLabel(graph, "S", "3999"))
        assertNull(router.routeToLabel(graph, "missing", "3650"))
    }

    @Test
    fun `starting at the destination door is an immediate arrival`() {
        val route = assertNotNull(router.routeToLabel(graph, "C", "3650"))
        assertEquals(listOf("C"), route.nodeIds)
        assertEquals(0.0, route.lengthPx, 1e-9)
    }

    @Test
    fun `destinations are room numbers only, sorted and distinct`() {
        assertEquals(listOf("3546", "3650", "3999"), graph.roomDestinations())
    }

    /** A.2 tests on the traced EH3 graph, when the fixture is available. */
    @Test
    fun `every EH3 room is reachable from START by its nearest door, matching Dijkstra`() {
        val file = listOf("../data/fixtures/maps", "data/fixtures/maps")
            .map { File(it, "eh-3-elevators-evac.graph.json") }
            .firstOrNull { it.exists() } ?: return
        val eh3 = FloorGraphJson.decode(file.readText())
        val rooms = eh3.roomDestinations()
        assertTrue(rooms.size >= 45, "expected the traced rooms, got ${rooms.size}")
        val shortest = dijkstra(eh3, eh3.startNodeId)
        for (room in rooms) {
            val route = assertNotNull(router.routeToLabel(eh3, eh3.startNodeId, room), room)
            assertConnected(eh3, route)
            assertTrue(route.nodeIds.last() in eh3.nodesForLabel(room), room)
            val best = eh3.nodesForLabel(room).minOf { shortest[it] ?: Double.POSITIVE_INFINITY }
            assertEquals(best, route.lengthPx, 1e-6, "A* vs Dijkstra for $room")
        }
    }

    private fun assertConnected(graph: FloorGraph, route: Route) {
        assertEquals(route.nodeIds.size - 1, route.edgeIds.size)
        val edges = graph.edges.associateBy { it.id }
        route.edgeIds.forEachIndexed { i, id ->
            val edge = assertNotNull(edges[id])
            assertEquals(setOf(route.nodeIds[i], route.nodeIds[i + 1]), setOf(edge.from, edge.to))
        }
        assertEquals(route.edgeIds.sumOf { edges.getValue(it).lengthPx }, route.lengthPx, 1e-6)
    }

    /** Plain Dijkstra from [source]: shortest lengthPx to every reachable node. */
    private fun dijkstra(graph: FloorGraph, source: String): Map<String, Double> {
        val adjacency = graph.adjacency()
        val best = hashMapOf(source to 0.0)
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        queue.add(source to 0.0)
        while (queue.isNotEmpty()) {
            val (node, cost) = queue.poll()
            if (cost > best.getValue(node)) continue
            for ((edge, next) in adjacency[node].orEmpty()) {
                val candidate = cost + edge.lengthPx
                if (candidate < (best[next] ?: Double.POSITIVE_INFINITY)) {
                    best[next] = candidate
                    queue.add(next to candidate)
                }
            }
        }
        return best
    }
}
