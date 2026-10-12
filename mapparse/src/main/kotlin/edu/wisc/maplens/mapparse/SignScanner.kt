package edu.wisc.maplens.mapparse

import android.graphics.Bitmap
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.SignObservation

/** Reads a room sign and matches it to a landmark in the graph. Owned by Area A. */
interface SignScanner {
    fun scan(frame: Bitmap, graph: FloorGraph, timestampNanos: Long): SignObservation?
}
