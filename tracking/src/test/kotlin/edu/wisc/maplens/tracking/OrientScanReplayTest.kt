package edu.wisc.maplens.tracking

import edu.wisc.maplens.model.FloorGraphJson
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Re-runs scans saved by the debug scan screen through today's analysis and solver, so tuning
 * can be checked against real hallway data without rescanning. Skipped unless MAPLENS_SCANS
 * points at a folder of `scan-*` directories (pulled from the phone's app files).
 */
class OrientScanReplayTest {
    @Test
    fun `saved scans are never confidently wrong`() {
        val root = System.getProperty("maplens.scans")?.let(::File)
            ?.takeIf { it.isDirectory } ?: return
        val maps = File(System.getProperty("maplens.fixtures"), "maps")
        val solver = OrientationSolver()
        val wrong = mutableListOf<String>()
        val scans = root.walk().filter { it.name == "scan.json" }.sortedBy { it.parentFile.name }
        scans.forEach { file ->
            val scan = Json.parseToJsonElement(file.readText()).jsonObject
            val graph = FloorGraphJson.decode(File(maps, scan.string("graph")).readText())
            val labels = graph.landmarks.map { it.label }.toSet()
            val hfov = scan.number("hfovRad")!!
            val observations = scan.getValue("frames").jsonArray.flatMap { element ->
                val f = element.jsonObject
                val pose =
                    CameraPose(f.number("yawRad")!!, f.number("pitchRad")!!, f.number("rollRad")!!)
                val frame =
                    ScanFrame(
                        pose,
                        f.getValue("width").jsonPrimitive.int,
                        f.getValue("height").jsonPrimitive.int,
                        hfov
                    )
                val vp = (f["vanishingPoint"] as? JsonObject)?.let {
                    VanishingPoint(
                        it.number("angleRad")!!,
                        it.number("strength")!!,
                        it.string("inView") == "true"
                    )
                }
                val texts = f["texts"]?.jsonArray.orEmpty().map {
                    TextHit(it.jsonObject.string("text"), it.jsonObject.number("centerX")!!)
                }
                scanObservations(frame, vp, texts, labels)
            }
            val result = solver.solve(graph, scan.string("nodeId"), observations)
            val truth = scan.number("trueOffsetRad")
            val error = truth?.let { t ->
                result.best?.let {
                    degrees(
                        abs(wrapHeading(it.offsetRad - t))
                    )
                }
            }
            println(
                "${file.parentFile.name} ${scan.string("nodeId")}: ${result.status} " +
                    "best ${result.best?.let { fmt(degrees(it.offsetRad)) }}° " +
                    "truth ${truth?.let { fmt(degrees(it)) }}° error ${error?.let(::fmt)}° " +
                    observations.groupingBy { it::class.simpleName }.eachCount()
            )
            if (result.status == OrientStatus.CLEAR && error != null && error > WRONG_DEG) {
                wrong += file.parentFile.name
            }
        }
        assertTrue(wrong.isEmpty(), "confidently wrong: $wrong")
    }

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun degrees(rad: Double) = Math.toDegrees(rad)
    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.1f", value)

    private companion object {
        /** Hand-set truth is itself only good to roughly this. */
        const val WRONG_DEG = 20.0
    }
}
