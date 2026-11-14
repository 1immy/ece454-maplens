package edu.wisc.maplens.mapparse

import edu.wisc.maplens.model.PixelPoint

/** Tolerances for [roomEntrances], in pixels of the 2000 px straightened poster. */
internal data class EntranceParams(
    /** Gaps in a wall up to this long are doorways (EH posters draw doors 20-40 px wide). */
    val maxDoorPx: Int = 42,
    /** A doorway is at least this many gap pixels; less is a wall drawn with a nick in it. */
    val minGapPixels: Int = 12,
    /** How far around the printed number a room may reach. */
    val windowPx: Int = 260,
    /** A sealed room bigger than this many pixels has leaked out: no clear doorway. */
    val maxRoomPixels: Int = 160_000,
)

/**
 * Doorways of the room whose number is printed at [label], as you'd find them with a paint
 * bucket: seal every wall gap up to door width, fill the room from its number, and every
 * sealed gap the fill touches is where paint would have leaked out, i.e. an entrance. Returns
 * each doorway's middle, or null when the room can't be told this way (its number sits on a
 * wall or in an open area, or the fill escapes because a wall is missing), so the caller can
 * fall back to the cheapest path out.
 *
 * [walls] is the poster's wall mask ([wallMask]), [width] its width. Plain arrays, no OpenCV,
 * so it runs per room in parallel and unit-tests on a laptop.
 */
internal fun roomEntrances(
    label: PixelPoint,
    walls: ByteArray,
    width: Int,
    params: EntranceParams = EntranceParams(),
): List<PixelPoint>? = roomEntrances(label, WallGrid(walls, width, params), params)

/**
 * The wall mask as booleans, with door-sized gaps sealed once for the whole poster: sealing it
 * again in every room's window cost ~2 s per poster.
 */
internal class WallGrid(
    walls: ByteArray,
    val width: Int,
    params: EntranceParams = EntranceParams()
) {
    val height = walls.size / width
    val wall = BooleanArray(walls.size) { walls[it] != 0.toByte() }
    val sealed = sealGaps(wall, width, height, params.maxDoorPx)
}

internal fun roomEntrances(
    label: PixelPoint,
    grid: WallGrid,
    params: EntranceParams = EntranceParams(),
): List<PixelPoint>? {
    val left = (label.x.toInt() - params.windowPx).coerceAtLeast(0)
    val top = (label.y.toInt() - params.windowPx).coerceAtLeast(0)
    val right = (label.x.toInt() + params.windowPx).coerceAtMost(grid.width)
    val bottom = (label.y.toInt() + params.windowPx).coerceAtMost(grid.height)
    val w = right - left
    val h = bottom - top
    if (w <= 2 || h <= 2) return null
    val wall = BooleanArray(w * h)
    val sealed = BooleanArray(w * h)
    for (y in 0 until h) {
        System.arraycopy(grid.wall, (y + top) * grid.width + left, wall, y * w, w)
        System.arraycopy(grid.sealed, (y + top) * grid.width + left, sealed, y * w, w)
    }

    // Fill the room from its number (or the nearest unsealed pixel to it).
    val seed = nearestOpen(sealed, w, h, label.x.toInt() - left, label.y.toInt() - top)
        ?: return null
    val room = BooleanArray(w * h)
    val queue = IntArray(w * h)
    // Neighbour offsets, allocated once: a fresh array per pixel cost ~2 s per poster in GC.
    val steps = intArrayOf(-1, 1, -w, w)
    var head = 0
    var tail = 0
    room[seed] = true
    queue[tail++] = seed
    while (head < tail) {
        val i = queue[head++]
        val x = i % w
        val y = i / w
        // Touching the window's edge: the room isn't closed within reach.
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) return null
        for (k in 0 until 4) {
            val n = i + steps[k]
            if (!room[n] && !sealed[n]) {
                room[n] = true
                queue[tail++] = n
            }
        }
        if (tail > params.maxRoomPixels) return null
    }

    // Sealed gap pixels the fill touches: where the paint would leak. Only the room's own
    // pixels are visited, not the whole window.
    val filled = tail
    val gaps = mutableListOf<Int>()
    for (q in 0 until filled) {
        val i = queue[q]
        for (k in 0 until 4) {
            val n = i + steps[k]
            if (sealed[n] && !wall[n]) gaps += n
        }
    }
    // Grow each touched gap through the rest of the sealed opening, then group.
    val seen = BooleanArray(w * h)
    val doorways = mutableListOf<PixelPoint>()
    for (start in gaps) {
        if (seen[start]) continue
        var count = 0
        var sx = 0.0
        var sy = 0.0
        head = 0
        tail = 0
        queue[tail++] = start
        seen[start] = true
        while (head < tail) {
            val i = queue[head++]
            count++
            sx += i % w
            sy += i / w
            val x = i % w
            val y = i / w
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val n = ny * w + nx
                if (!seen[n] && sealed[n] && !wall[n] && !room[n]) {
                    seen[n] = true
                    queue[tail++] = n
                }
            }
        }
        if (count >= params.minGapPixels) {
            doorways += PixelPoint(left + sx / count, top + sy / count)
        }
    }
    return doorways
}

/**
 * Walls with every gap up to [maxGap] long closed, across rows and down columns: a doorway in a
 * vertical wall is a short vertical run of floor between two wall pixels, and so on. A room or
 * corridor is wider than a door, so its floor stays open.
 */
internal fun sealGaps(wall: BooleanArray, w: Int, h: Int, maxGap: Int): BooleanArray {
    val sealed = wall.copyOf()
    // Down each column: a short run of floor with wall above and below it.
    for (x in 0 until w) {
        var lastWall = -1
        for (y in 0 until h) {
            if (!wall[y * w + x]) continue
            val gap = y - lastWall - 1
            if (lastWall >= 0 && gap in 1..maxGap &&
                runsAlong(wall, w, h, x, lastWall, y, vertical = true)
            ) {
                for (yy in lastWall + 1 until y) sealed[yy * w + x] = true
            }
            lastWall = y
        }
    }
    // Along each row.
    for (y in 0 until h) {
        var lastWall = -1
        for (x in 0 until w) {
            if (!wall[y * w + x]) continue
            val gap = x - lastWall - 1
            if (lastWall >= 0 && gap in 1..maxGap &&
                runsAlong(wall, w, h, y, lastWall, x, vertical = false)
            ) {
                for (xx in lastWall + 1 until x) sealed[y * w + xx] = true
            }
            lastWall = x
        }
    }
    return sealed
}

/**
 * Whether the wall pixels at both ends of a gap continue as a wall in the gap's direction, so
 * the gap is a break in one wall (a doorway) and not the floor between two walls facing each
 * other across a narrow room.
 */
private fun runsAlong(
    wall: BooleanArray,
    w: Int,
    h: Int,
    line: Int,
    from: Int,
    to: Int,
    vertical: Boolean,
): Boolean {
    fun isWall(along: Int, across: Int): Boolean {
        val (x, y) = if (vertical) across to along else along to across
        return x in 0 until w && y in 0 until h && wall[y * w + x]
    }
    // The wall before the gap reaches back, the one after reaches on, both along the line.
    val before = (1..WALL_RUN).count { isWall(from - it + 1, line) } >= WALL_RUN - 1
    val after = (1..WALL_RUN).count { isWall(to + it - 1, line) } >= WALL_RUN - 1
    return before && after
}

/** Wall pixels needed on each side of a gap, along it, for the gap to be a doorway. */
private const val WALL_RUN = 6

/** The open pixel nearest ([x], [y]), searched outwards a little: a number can touch ink. */
private fun nearestOpen(sealed: BooleanArray, w: Int, h: Int, x: Int, y: Int): Int? {
    for (r in 0..SEED_SEARCH) {
        for (dy in -r..r) for (dx in -r..r) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
            val nx = x + dx
            val ny = y + dy
            val inside = nx in 1 until w - 1 && ny in 1 until h - 1
            if (inside && !sealed[ny * w + nx]) return ny * w + nx
        }
    }
    return null
}

private const val SEED_SEARCH = 12
