package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import edu.wisc.maplens.tracking.OrientObservation.CorridorAxis
import edu.wisc.maplens.tracking.OrientObservation.Door
import edu.wisc.maplens.tracking.OrientObservation.Opening
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Simulated scans at every node of the traced EH3 elevators graph: what a turn on the spot
 * would see there, with bearing noise. Checks the solver is never confidently wrong.
 */
class OrientationSolverEh3Test {
    private val graph: FloorGraph? = System.getProperty("maplens.fixtures")
        ?.let { File(it, "maps/eh-3-elevators-evac.graph.json") }
        ?.takeIf { it.isFile }
        ?.let { FloorGraphJson.decode(it.readText()) }
    private val solver = OrientationSolver()
    private val trueOffset = Math.toRadians(73.0)

    private fun corridorBearings(graph: FloorGraph, nodeId: String): List<Double> {
        val here = graph.nodes.getValue(nodeId).position
        return graph.adjacency()[nodeId].orEmpty().map { (edge, other) ->
            val path = if (edge.from == nodeId) edge.polyline else edge.polyline.asReversed()
            val next = path.firstOrNull { it.distanceTo(here) > 1e-6 }
                ?: graph.nodes.getValue(other).position
            atan2(next.y - here.y, next.x - here.x)
        }
    }

    /** Openings and axes down every corridor at the node, each off by up to ±noiseDeg. */
    private fun lineScan(graph: FloorGraph, nodeId: String, random: Random, noiseDeg: Double) =
        corridorBearings(graph, nodeId).flatMap { mapRad ->
            fun seen() =
                mapRad - trueOffset + Math.toRadians(random.nextDouble(-noiseDeg, noiseDeg))
            listOf(Opening(seen()), CorridorAxis(seen()))
        }

    /** A labelled door one corridor away, as seen from [nodeId], if there is one. */
    private fun neighbourDoor(graph: FloorGraph, nodeId: String, random: Random): Door? {
        val here = graph.nodes.getValue(nodeId).position
        val neighbours = graph.adjacency()[nodeId].orEmpty().map { it.second }.toSet()
        val landmark = graph.landmarks
            .filter { it.nodeId in neighbours && it.label.first().isDigit() }
            .minByOrNull { graph.nodes.getValue(it.nodeId).position.distanceTo(here) }
            ?: return null
        val door = graph.nodes.getValue(landmark.nodeId).position
        val mapRad = atan2(door.y - here.y, door.x - here.x)
        return Door(
            landmark.label,
            mapRad - trueOffset + Math.toRadians(random.nextDouble(-3.0, 3.0))
        )
    }

    private fun errorDeg(offsetRad: Double) =
        abs(
            Math.toDegrees(
                atan2(
                    kotlin.math.sin(offsetRad - trueOffset),
                    kotlin.math.cos(offsetRad - trueOffset)
                )
            )
        )

    @Test
    fun `EH3 scans are never confidently wrong and a nearby door settles most nodes`() {
        val graph = graph ?: return
        val random = Random(454)
        val nodes = graph.nodes.keys.filter { graph.adjacency()[it].orEmpty().isNotEmpty() }
        var linesClear = 0
        var doorNodes = 0
        var doorClear = 0
        for (node in nodes) {
            val lines = solver.solve(graph, node, lineScan(graph, node, random, noiseDeg = 5.0))
            if (lines.status == OrientStatus.CLEAR) {
                linesClear++
                assertTrue(
                    errorDeg(lines.best!!.offsetRad) <= 10.0,
                    "lines-only CLEAR but wrong at $node"
                )
            } else {
                // Ambiguous must still offer the truth among its candidates.
                assertTrue(
                    lines.candidates.any { errorDeg(it.offsetRad) <= 10.0 },
                    "truth missing at $node"
                )
            }
            val door = neighbourDoor(graph, node, random) ?: continue
            doorNodes++
            val withDoor = solver.solve(graph, node, lineScan(graph, node, random, 5.0) + door)
            if (withDoor.status == OrientStatus.CLEAR) {
                assertTrue(errorDeg(withDoor.best!!.offsetRad) <= 10.0, "door scan wrong at $node")
                doorClear++
            } else {
                assertTrue(withDoor.candidates.any { errorDeg(it.offsetRad) <= 10.0 }, "lost $node")
            }
        }
        // A sign right beside you is imprecise, so a few junctions may still need a tap.
        assertTrue(doorClear >= doorNodes * 9 / 10, "door settled only $doorClear/$doorNodes")
        println(
            "EH3 orient: ${nodes.size} nodes, lines-only CLEAR $linesClear; " +
                "with a neighbouring door $doorClear/$doorNodes CLEAR"
        )
    }
}
