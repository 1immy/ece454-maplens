package edu.wisc.maplens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.FloorGraphJson
import java.io.File

/**
 * Bundled floor maps: the verified graph and the straightened image it was traced on.
 *
 * Assets come from data/fixtures/maps (see app/build.gradle.kts), so `name` is the
 * file stem there, e.g. "eh-3-elevators-evac". Graph coordinates are pixels on the
 * matching image. Call these off the UI thread; the image is ~2000 px wide.
 */
object MapAssets {
    /** EH 3rd floor, elevator evacuation poster: 75 nodes, 55 rooms, START at the elevators. */
    const val EH3_ELEVATORS = "eh-3-elevators-evac"

    fun loadGraph(context: Context, name: String = EH3_ELEVATORS): FloorGraph =
        context.assets.open("$name.graph.json").bufferedReader().use {
            FloorGraphJson.decode(it.readText())
        }

    fun loadImage(context: Context, name: String = EH3_ELEVATORS): Bitmap =
        context.assets.open("$name.jpg").use { BitmapFactory.decodeStream(it) }
            ?: error("$name.jpg could not be decoded (is Git LFS installed? run `git lfs pull`)")

    /** Maps the user scanned and verified, kept in app storage (#65). */
    fun savedMaps(context: Context): MapStore = MapStore(File(context.filesDir, "maps"))

    fun loadImage(map: SavedMap): Bitmap =
        BitmapFactory.decodeFile(map.imageFile.path)
            ?: error("${map.name}: image could not be read")
}
