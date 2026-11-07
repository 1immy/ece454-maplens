package edu.wisc.maplens.nav

/** A room's outline as a box on the map, in map pixels. */
data class RoomBox(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * Finds each room's outline from its printed number, wall to wall.
 *
 * Every number starts as a box around its own print. All boxes then grow together, one line
 * per side per round. A side stops when its next line is mostly ink (a wall: it runs the
 * width of the room, while text, door swings and furniture don't), or when that line would
 * enter space another room's box already claimed, so neighbours meet at the wall between
 * them even where it has a doorway. A box that runs away past [maxShare] of the map is
 * not a room (an open area or a misread number) and gets no outline.
 *
 * Works on the walls as printed ([WallGrid.levels] first level), so small rooms keep their
 * full size. Pure Kotlin so it unit-tests without a phone.
 */
object RoomBounds {
    /** A line with at least this share of ink across the box is a wall. */
    const val WALL_SHARE = 0.5

    fun grow(
        walls: WallGrid,
        labels: List<LabelBox>,
        maxShare: Double = 0.06,
        wallShare: Double = WALL_SHARE,
    ): Map<String, RoomBox> {
        val w = walls.width
        val h = walls.height
        val s = walls.scale
        // Printed numbers are ink but not walls: blank them out so they never stop a side.
        val ink = walls.levels.first().copyOf()
        val boxes = labels.map { box ->
            val l = (box.left / s).toInt().coerceIn(0, w - 1)
            val t = (box.top / s).toInt().coerceIn(0, h - 1)
            val r = (box.right / s).toInt().coerceIn(l, w - 1)
            val b = (box.bottom / s).toInt().coerceIn(t, h - 1)
            for (y in maxOf(0, t - 1)..minOf(h - 1, b + 1)) {
                for (x in maxOf(0, l - 1)..minOf(w - 1, r + 1)) ink[y * w + x] = false
            }
            intArrayOf(l, t, r, b)
        }
        val owner = IntArray(w * h) { -1 }
        boxes.forEachIndexed { k, (l, t, r, b) -> claim(owner, w, k, l, t, r, b) }
        val alive = BooleanArray(boxes.size) { true }
        val limit = maxShare * w * h

        fun inkShare(x0: Int, y0: Int, x1: Int, y1: Int, dx: Int, dy: Int): Double {
            // The line plus the next one out, so a slightly tilted wall still counts.
            var hits = 0
            var count = 0
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val x2 = (x + dx).coerceIn(0, w - 1)
                    val y2 = (y + dy).coerceIn(0, h - 1)
                    if (ink[y * w + x] || ink[y2 * w + x2]) hits++
                    count++
                }
            }
            return hits.toDouble() / count
        }

        fun free(k: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val o = owner[y * w + x]
                    if (o >= 0 && o != k) return false
                }
            }
            return true
        }

        /** Moves one side of box [k] out by a line ([dx], [dy] point outward) if it may. */
        fun push(k: Int, box: IntArray, dx: Int, dy: Int): Boolean {
            val (l, t, r, b) = box
            val x0 = if (dx < 0) l - 1 else if (dx > 0) r + 1 else l
            val x1 = if (dx < 0) l - 1 else if (dx > 0) r + 1 else r
            val y0 = if (dy < 0) t - 1 else if (dy > 0) b + 1 else t
            val y1 = if (dy < 0) t - 1 else if (dy > 0) b + 1 else b
            if (x0 < 0 || y0 < 0 || x1 >= w || y1 >= h) return false
            if (!free(k, x0, y0, x1, y1) || inkShare(x0, y0, x1, y1, dx, dy) >= wallShare) {
                return false
            }
            when {
                dx < 0 -> box[0] = x0
                dx > 0 -> box[2] = x1
                dy < 0 -> box[1] = y0
                else -> box[3] = y1
            }
            claim(owner, w, k, x0, y0, x1, y1)
            return true
        }

        var moved = true
        while (moved) {
            moved = false
            boxes.forEachIndexed { k, box ->
                if (!alive[k]) return@forEachIndexed
                for ((dx, dy) in SIDES) if (push(k, box, dx, dy)) moved = true
                val (l3, t3, r3, b3) = box
                if ((r3 - l3 + 1).toDouble() * (b3 - t3 + 1) > limit) {
                    // Not a room: give its space back so neighbours can still grow into it.
                    alive[k] = false
                    for (y in t3..b3) {
                        for (x in l3..r3) if (owner[y * w + x] == k) owner[y * w + x] = -1
                    }
                }
            }
        }
        val out = LinkedHashMap<String, RoomBox>()
        boxes.forEachIndexed { k, (l, t, r, b) ->
            // A box that never left its own print found no walls to stop at; skip it too.
            if (!alive[k] || (r - l) * (b - t) <= area(labels[k], s)) return@forEachIndexed
            out.putIfAbsent(
                labels[k].label,
                // Each side stopped one line short of its wall (it looks a line ahead for tilt);
                // reach out onto the wall line so the outline sits on it.
                RoomBox(
                    maxOf(0, l - 1) * s.toDouble(),
                    maxOf(0, t - 1) * s.toDouble(),
                    minOf(w, r + 2) * s.toDouble(),
                    minOf(h, b + 2) * s.toDouble(),
                ),
            )
        }
        return out
    }

    private val SIDES = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)

    private fun area(box: LabelBox, scale: Int) =
        ((box.right - box.left) / scale) * ((box.bottom - box.top) / scale)

    private fun claim(owner: IntArray, w: Int, k: Int, l: Int, t: Int, r: Int, b: Int) {
        for (y in t..b) for (x in l..r) if (owner[y * w + x] < 0) owner[y * w + x] = k
    }
}
