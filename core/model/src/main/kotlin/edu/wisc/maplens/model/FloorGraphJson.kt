package edu.wisc.maplens.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * On-disk format for [FloorGraph] (`*.graph.json`). See data/fixtures/maps/README.md.
 *
 * Decoding is lenient so hand-traced files stay short: edge `id`, `polyline` and
 * `lengthPx`, landmark `ocrConfidence` and graph `verified` are optional. Decoding
 * does not judge the graph; call [FloorGraph.validate] for that.
 */
object FloorGraphJson {
    const val SCHEMA_VERSION = 1

    private val pretty = Json { prettyPrint = true }

    fun encode(graph: FloorGraph): String =
        pretty.encodeToString(JsonElement.serializer(), toJson(graph))

    fun decode(text: String): FloorGraph {
        val root = Json.parseToJsonElement(text).jsonObject
        val version = root.required("schemaVersion").jsonPrimitive.int
        require(version == SCHEMA_VERSION) {
            "unsupported schemaVersion $version (expected $SCHEMA_VERSION)"
        }

        val nodeList = root.required("nodes").jsonArray.map { element ->
            val n = element.jsonObject
            Node(
                id = n.string("id"),
                position = PixelPoint(n.number("x"), n.number("y")),
                type = NodeType.valueOf(n.string("type")),
            )
        }
        val nodes = LinkedHashMap<String, Node>()
        for (node in nodeList) {
            require(nodes.put(node.id, node) == null) { "duplicate node id '${node.id}'" }
        }

        val edges = root.required("edges").jsonArray.mapIndexed { index, element ->
            val e = element.jsonObject
            val from = e.string("from")
            val to = e.string("to")
            val polyline = e["polyline"]?.jsonArray?.map { point ->
                val xy = point.jsonArray
                require(xy.size == 2) { "polyline points are [x, y]" }
                PixelPoint(xy[0].jsonPrimitive.double, xy[1].jsonPrimitive.double)
            } ?: listOfNotNull(nodes[from]?.position, nodes[to]?.position)
            Edge(
                id = e["id"]?.jsonPrimitive?.content ?: "e${index + 1}",
                from = from,
                to = to,
                polyline = polyline,
                lengthPx = e["lengthPx"]?.jsonPrimitive?.double ?: polylineLength(polyline),
            )
        }

        val landmarks = root["landmarks"]?.jsonArray?.map { element ->
            val l = element.jsonObject
            Landmark(
                label = l.string("label"),
                nodeId = l.string("nodeId"),
                ocrConfidence = l["ocrConfidence"]?.jsonPrimitive?.float ?: 1.0f,
            )
        }.orEmpty()

        return FloorGraph(
            buildingId = root.string("buildingId"),
            floor = root.string("floor"),
            nodes = nodes,
            edges = edges,
            landmarks = landmarks,
            startNodeId = root.string("startNodeId"),
            verified = root["verified"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    fun polylineLength(points: List<PixelPoint>): Double =
        points.zipWithNext { a, b -> a.distanceTo(b) }.sum()

    private fun toJson(graph: FloorGraph): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("buildingId", graph.buildingId)
        put("floor", graph.floor)
        put("startNodeId", graph.startNodeId)
        put("verified", graph.verified)
        putJsonArray("nodes") {
            for (node in graph.nodes.values) {
                addJsonObject {
                    put("id", node.id)
                    put("type", node.type.name)
                    put("x", node.position.x)
                    put("y", node.position.y)
                }
            }
        }
        putJsonArray("edges") {
            for (edge in graph.edges) {
                addJsonObject {
                    put("id", edge.id)
                    put("from", edge.from)
                    put("to", edge.to)
                    put("lengthPx", edge.lengthPx)
                    putJsonArray("polyline") {
                        for (point in edge.polyline) {
                            addJsonArray {
                                add(point.x)
                                add(point.y)
                            }
                        }
                    }
                }
            }
        }
        putJsonArray("landmarks") {
            for (landmark in graph.landmarks) {
                addJsonObject {
                    put("label", landmark.label)
                    put("nodeId", landmark.nodeId)
                    put("ocrConfidence", landmark.ocrConfidence)
                }
            }
        }
    }

    private fun JsonObject.required(key: String): JsonElement =
        this[key] ?: throw IllegalArgumentException("missing '$key'")

    private fun JsonObject.string(key: String): String = required(key).jsonPrimitive.content

    private fun JsonObject.number(key: String): Double = required(key).jsonPrimitive.double
}
