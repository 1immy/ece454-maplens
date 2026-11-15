package edu.wisc.maplens

import android.content.Context
import edu.wisc.maplens.model.FloorGraph
import java.text.DateFormat
import java.util.Date

/**
 * A name for a newly scanned map from what the scan read: its floor and the span of its room
 * numbers ("3rd floor · Rooms 3332–3662"). Posters print no building name, so the user adds
 * it when renaming. Falls back to the scan time when no room numbers were read.
 */
internal fun Context.suggestedMapName(graph: FloorGraph): String {
    val numbers = graph.landmarks.map { it.label }
        .filter { label -> label.isNotEmpty() && label.all(Char::isDigit) }
        .map { it.toInt() }
    if (numbers.isEmpty()) {
        val stamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date())
        return getString(R.string.map_default_name, stamp)
    }
    val floor = graph.floor.toIntOrNull()
        ?: numbers.groupingBy { it.toString().first().digitToInt() }.eachCount()
            .maxBy { it.value }.key
    val onFloor = numbers.filter { it.toString().first().digitToInt() == floor }
        .ifEmpty { numbers }
    return getString(
        R.string.map_suggested_name,
        ordinalFloor(floor),
        onFloor.min(),
        onFloor.max(),
    )
}

/** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th, 21st. */
internal fun ordinalFloor(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix floor"
}
