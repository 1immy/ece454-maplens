package edu.wisc.maplens.model

/**
 * T-junction from slide 10:
 *
 *   W --- D3650 --- J --- E
 *                   |
 *                   S (You Are Here)
 */
object TestGraphs {
    fun tJunction(): FloorGraph {
        val nodes = listOf(
            Node("W", PixelPoint(0.0, 0.0), NodeType.DEAD_END),
            Node("D3650", PixelPoint(100.0, 0.0), NodeType.DOOR),
            Node("J", PixelPoint(200.0, 0.0), NodeType.JUNCTION),
            Node("E", PixelPoint(400.0, 0.0), NodeType.DEAD_END),
            Node("S", PixelPoint(200.0, 150.0), NodeType.START),
        ).associateBy { it.id }
        val edges = listOf(
            straight("e1", nodes, "W", "D3650"),
            straight("e2", nodes, "D3650", "J"),
            straight("e3", nodes, "J", "E"),
            straight("e4", nodes, "S", "J"),
        )
        return FloorGraph(
            buildingId = "engineering-hall",
            floor = "3",
            nodes = nodes,
            edges = edges,
            landmarks = listOf(Landmark("3650", "D3650", 0.95f)),
            startNodeId = "S",
        )
    }

    private fun straight(id: String, nodes: Map<String, Node>, from: String, to: String): Edge {
        val a = nodes.getValue(from).position
        val b = nodes.getValue(to).position
        return Edge(id, from, to, listOf(a, b), a.distanceTo(b))
    }
}
