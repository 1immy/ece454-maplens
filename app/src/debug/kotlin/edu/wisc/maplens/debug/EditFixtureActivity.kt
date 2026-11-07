package edu.wisc.maplens.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import edu.wisc.maplens.GraphEditorActivity
import edu.wisc.maplens.MapAssets
import edu.wisc.maplens.MapDraft
import edu.wisc.maplens.MapStore
import edu.wisc.maplens.roomsFromLabels

/**
 * Debug only: opens a copy of the traced EH3 graph in the editor, unverified. The bundled files
 * are read-only, and the copy only ever lives in app storage.
 */
class EditFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val copied = runCatching {
                val graph = MapAssets.loadGraph(this).copy(verified = false)
                MapStore(this).startDraft(MapDraft(graph, graph.roomsFromLabels())) { file ->
                    assets.open("${MapAssets.EH3_ELEVATORS}.jpg").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            runOnUiThread {
                copied.onSuccess {
                    startActivity(Intent(this, GraphEditorActivity::class.java))
                }.onFailure {
                    Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }
}
