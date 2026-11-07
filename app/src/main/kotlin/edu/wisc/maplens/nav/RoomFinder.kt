package edu.wisc.maplens.nav

import edu.wisc.maplens.model.PixelPoint
import kotlin.math.hypot

/**
 * Walls of a floor plan on a coarse grid: `true` = wall. [scale] map pixels per cell.
 * Built once per map (see [MapRooms]); several [levels] close door gaps of growing width,
 * so a room whose doorway leaks at one level can still be enclosed at the next. Levels start
 * at the walls as printed, so small rooms close before thickening swallows them.
 */
class WallGrid(
    val width: Int,
    val height: Int,
    val scale: Int,
    val levels: List<BooleanArray>,
    /** How many cells each level thickened the walls by. */
    val radii: List<Int> = List(levels.size) { 0 },
) {
    init {
        require(levels.isNotEmpty() && levels.all { it.size == width * height })
        require(radii.size == levels.size)
    }

    companion object {
        /**
         * Grid from luminance values (0..255, row-major, [width] × [height] cells): darker than
         * [threshold] is wall, then each of [closeRadii] thickens walls by that many cells.
         * The default threshold comes from the image itself ([otsu]), so a dim photo, glare or
         * a lighter print still splits ink from paper without tuning per map.
         */
        fun fromLuminance(
            luma: IntArray,
            width: Int,
            height: Int,
            scale: Int,
            threshold: Int = otsu(luma),
            closeRadii: List<Int> = listOf(0, 1, 2, 3, 5, 7),
        ): WallGrid {
            val raw = BooleanArray(width * height) { luma[it] < threshold }
            val levels = closeRadii.map { dilate(raw, width, height, it) }
            return WallGrid(width, height, scale, levels, closeRadii)
        }

        /**
         * Otsu's threshold: the grey level that best splits the histogram into two classes
         * (ink and paper) by maximising the variance between them.
         */
        fun otsu(luma: IntArray): Int {
            val histogram = IntArray(256)
            for (v in luma) histogram[v.coerceIn(0, 255)]++
            val total = luma.size.toDouble()
            val sumAll = (0..255).sumOf { it.toDouble() * histogram[it] }
            var below = 0.0
            var sumBelow = 0.0
            var best = 0.0
            var threshold = 128
            for (t in 0 until 256) {
                below += histogram[t]
                if (below == 0.0) continue
                val above = total - below
                if (above == 0.0) break
                sumBelow += t.toDouble() * histogram[t]
                val meanBelow = sumBelow / below
                val meanAbove = (sumAll - sumBelow) / above
                val between = below * above * (meanBelow - meanAbove) * (meanBelow - meanAbove)
                if (between > best) {
                    best = between
                    threshold = t + 1
                }
            }
            return threshold
        }

        /** Square dilation by [r] cells, as two separable sliding-window passes. */
        fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
            if (r <= 0) return src.copyOf()
            val rows = BooleanArray(w * h)
            for (y in 0 until h) {
                var count = 0
                for (x in 0 until minOf(r, w)) if (src[y * w + x]) count++
                for (x in 0 until w) {
                    if (x + r < w && src[y * w + x + r]) count++
                    if (x - r - 1 >= 0 && src[y * w + x - r - 1]) count--
                    rows[y * w + x] = count > 0
                }
            }
            val out = BooleanArray(w * h)
            for (x in 0 until w) {
                var count = 0
                for (y in 0 until minOf(r, h)) if (rows[y * w + x]) count++
                for (y in 0 until h) {
                    if (y + r < h && rows[(y + r) * w + x]) count++
                    if (y - r - 1 >= 0 && rows[(y - r - 1) * w + x]) count--
                    out[y * w + x] = count > 0
                }
            }
            return out
        }
    }
}

/** A printed room number and its box on the map, in map pixels. */
data class LabelBox(
    val label: String,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    val center get() = PixelPoint((left + right) / 2, (top + bottom) / 2)

    fun contains(p: PixelPoint, slack: Double) =
        p.x >= left - slack && p.x <= right + slack && p.y >= top - slack && p.y <= bottom + slack
}

/** The enclosed area a tap fell in, as a cell mask over the box [x0], [y0] .. size [w] × [h]. */
class RoomArea(
    val x0: Int,
    val y0: Int,
    val w: Int,
    val h: Int,
    val scale: Int,
    val cells: BooleanArray,
    /** Cells the walls were thickened by; grow the area this much to reach the real walls. */
    val pad: Int = 0,
) {
    operator fun contains(p: PixelPoint): Boolean {
        val x = (p.x / scale).toInt() - x0
        val y = (p.y / scale).toInt() - y0
        return x in 0 until w && y in 0 until h && cells[y * w + x]
    }

    /**
     * Whether [box], grown by [slackPx], overlaps the area. A printed number is dark ink the
     * fill flows around, so its own cells are never in the area, only the paper around it.
     */
    fun touches(box: LabelBox, slackPx: Double): Boolean {
        val left = ((box.left - slackPx) / scale).toInt() - x0
        val right = ((box.right + slackPx) / scale).toInt() - x0
        val top = ((box.top - slackPx) / scale).toInt() - y0
        val bottom = ((box.bottom + slackPx) / scale).toInt() - y0
        for (y in maxOf(0, top)..minOf(h - 1, bottom)) {
            for (x in maxOf(0, left)..minOf(w - 1, right)) if (cells[y * w + x]) return true
        }
        return false
    }
}

data class RoomHit(val label: String, val area: RoomArea?)

/**
 * "Which room did I tap?" on a floor plan, the way a maps app answers it:
 *
 * 1. A tap on a printed room number is that room.
 * 2. Otherwise flood-fill the walls around the tap; the room is the printed number inside
 *    that enclosed area (closest to the tap if several).
 * 3. With no number found inside, the door that touches the area from beside it wins.
 * 4. In a corridor (the fill runs away) only a door within [doorRadiusPx] counts.
 *
 * Pure Kotlin so it unit-tests without a phone. Not thread safe; call from one thread.
 */
class RoomFinder(
    private val walls: WallGrid,
    labels: List<LabelBox>,
    private val doors: Map<String, List<PixelPoint>>,
    /** An area bigger than this share of the grid is a corridor or outside, not a room. */
    private val maxRoomShare: Double = 0.06,
    /** Smaller than this share is a pocket in hatching or print, not a room. */
    private val minRoomShare: Double = 0.00005,
    /** Corridor centre lines (the graph's edges), so a tap in a corridor selects no room. */
    private val corridors: List<Pair<PixelPoint, PixelPoint>> = emptyList(),
    /** Taps closer than this to a corridor centre line are in the corridor. */
    private val corridorHalfWidthPx: Double = 0.0,
) {
    var labels: List<LabelBox> = labels

    fun roomAt(tap: PixelPoint, doorRadiusPx: Double): RoomHit? {
        labels.firstOrNull { it.contains(tap, LABEL_SLACK_PX) }?.let { box ->
            return RoomHit(box.label, areaAround(box.center, box))
        }
        // Corridors run along the graph on every map, and the doors sit on that line too, so a
        // corridor tap picks nothing rather than whichever door happens to be closest.
        if (inCorridor(tap)) return null
        val area = areaAround(tap)
        if (area != null) {
            val inside = labels.filter { area.touches(it, slackFor(area)) }
            inside.minByOrNull { it.center.distanceTo(tap) }?.let { return RoomHit(it.label, area) }
            doorBeside(area)?.let { return RoomHit(it, area) }
        }
        return nearestDoor(tap, doorRadiusPx)?.let { RoomHit(it, null) }
    }

    /**
     * The enclosed area around [p], trying each wall level until one encloses a single room.
     *
     * A fill has leaked through a doorway when it runs away (past [maxRoomShare], typically
     * into the corridors) or when it reaches more than one printed room number. Both signs
     * hold on any floor plan, so neither needs tuning per map; thicker walls at the next
     * level then close the doorway. [own] is the tapped number, which is always allowed. If
     * every level still reaches several numbers, the most closed-off area is returned.
     */
    fun areaAround(p: PixelPoint, own: LabelBox? = null): RoomArea? {
        var fallback: RoomArea? = null
        walls.levels.forEachIndexed { i, level ->
            val area = fill(p, level, walls.radii[i]) ?: return@forEachIndexed
            if (area.cells.count { it } < minRoomShare * walls.width * walls.height) {
                return@forEachIndexed
            }
            val others = labels.count { it != own && area.touches(it, slackFor(area)) }
            val rooms = others + if (own != null) 1 else 0
            if (rooms <= 1) return area
            fallback = area
        }
        // No wall level separates this room from its neighbours (a row of offices opening onto
        // one strip): split the leaked area between the numbers in it and keep the tapped one.
        return fallback?.let { splitByLabels(it, p, own) }
    }

    /**
     * Grows outward from every printed number in [area] at once, one cell per step and only
     * through the area, so each cell goes to the number that reaches it first. Within walls
     * this puts the boundaries on the partitions between neighbouring rooms. Returns the part
     * owned by [own], or else by the number that reached [p].
     */
    private fun splitByLabels(area: RoomArea, p: PixelPoint, own: LabelBox?): RoomArea {
        val slack = slackFor(area)
        val seeds = labels.filter { area.touches(it, slack) }
        if (seeds.size < 2) return area
        val w = area.w
        val h = area.h
        val s = area.scale
        val owner = IntArray(w * h) { -1 }
        val queue = ArrayDeque<Int>()
        seeds.forEachIndexed { k, box ->
            val left = ((box.left - slack) / s).toInt() - area.x0
            val right = ((box.right + slack) / s).toInt() - area.x0
            val top = ((box.top - slack) / s).toInt() - area.y0
            val bottom = ((box.bottom + slack) / s).toInt() - area.y0
            for (y in maxOf(0, top)..minOf(h - 1, bottom)) {
                for (x in maxOf(0, left)..minOf(w - 1, right)) {
                    val c = y * w + x
                    if (area.cells[c] && owner[c] < 0) {
                        owner[c] = k
                        queue.addLast(c)
                    }
                }
            }
        }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            val x = c % w
            for (n in intArrayOf(c - 1, c + 1, c - w, c + w)) {
                if (n < 0 || n >= w * h) continue
                if ((n == c - 1 && x == 0) || (n == c + 1 && x == w - 1)) continue
                if (area.cells[n] && owner[n] < 0) {
                    owner[n] = owner[c]
                    queue.addLast(n)
                }
            }
        }
        val target = own?.let { seeds.indexOf(it) }?.takeIf { it >= 0 } ?: run {
            val px = ((p.x / s).toInt() - area.x0).coerceIn(0, w - 1)
            val py = ((p.y / s).toInt() - area.y0).coerceIn(0, h - 1)
            val at = nearestTrue(BooleanArray(w * h) { owner[it] >= 0 }, w, h, py * w + px)
            at?.let { owner[it] } ?: return area
        }
        val mine = BooleanArray(w * h) { owner[it] == target }
        return RoomArea(area.x0, area.y0, w, h, s, mine, area.pad)
    }

    /**
     * Thickened walls also cover the paper around a printed number, so the area stops short
     * of it by the same amount; reach that much further when asking if a number is inside.
     */
    private fun slackFor(area: RoomArea) = LABEL_SLACK_PX + area.pad * walls.scale

    private fun inCorridor(p: PixelPoint): Boolean =
        corridorHalfWidthPx > 0 &&
            corridors.any { (a, b) -> segmentDistance(p, a, b) < corridorHalfWidthPx }

    private fun segmentDistance(p: PixelPoint, a: PixelPoint, b: PixelPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) {
            0.0
        } else {
            (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(
                0.0,
                1.0
            )
        }
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }

    private fun fill(p: PixelPoint, wall: BooleanArray, pad: Int): RoomArea? {
        val w = walls.width
        val h = walls.height
        val start = openCellNear((p.x / walls.scale).toInt(), (p.y / walls.scale).toInt(), wall)
            ?: return null
        val limit = (maxRoomShare * w * h).toInt()
        val seen = BooleanArray(w * h)
        val queue = IntArray(limit + 4)
        var head = 0
        var tail = 0
        queue[tail++] = start
        seen[start] = true
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        while (head < tail) {
            val c = queue[head++]
            val x = c % w
            val y = c / w
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            for (n in intArrayOf(c - 1, c + 1, c - w, c + w)) {
                if (n < 0 || n >= w * h) continue
                if ((n == c - 1 && x == 0) || (n == c + 1 && x == w - 1)) continue
                if (seen[n] || wall[n]) continue
                if (tail > limit) return null
                seen[n] = true
                queue[tail++] = n
            }
        }
        val aw = maxX - minX + 1
        val ah = maxY - minY + 1
        val cells = BooleanArray(aw * ah)
        for (i in 0 until tail) {
            val c = queue[i]
            cells[(c / w - minY) * aw + (c % w - minX)] = true
        }
        val startCell = (start / w - minY) * aw + (start % w - minX)
        return RoomArea(minX, minY, aw, ah, walls.scale, trimTails(cells, aw, ah, startCell), pad)
    }

    /** Taps usually land on dark print (a number, a wall); start from the nearest open cell. */
    private fun openCellNear(cx: Int, cy: Int, wall: BooleanArray): Int? {
        val w = walls.width
        val h = walls.height
        for (r in 0..OPEN_SEARCH_CELLS) {
            var best: Int? = null
            var bestD = Int.MAX_VALUE
            for (dy in -r..r) {
                for (dx in -r..r) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                    val x = cx + dx
                    val y = cy + dy
                    if (x !in 0 until w || y !in 0 until h || wall[y * w + x]) continue
                    val d = dx * dx + dy * dy
                    if (d < bestD) {
                        bestD = d
                        best = y * w + x
                    }
                }
            }
            if (best != null) return best
        }
        return null
    }

    /**
     * A door "beside" the area: level with its span (so a room across the corridor, whose
     * door sits at the same distance, is not picked) and close to one of its cells.
     */
    private fun doorBeside(area: RoomArea): String? {
        val s = walls.scale.toDouble()
        val left = area.x0 * s
        val right = (area.x0 + area.w) * s
        val top = area.y0 * s
        val bottom = (area.y0 + area.h) * s
        var best: Pair<Double, String>? = null
        for ((label, points) in doors) {
            for (door in points) {
                val beside = door.x in left..right || door.y in top..bottom
                if (!beside) continue
                val d = distanceToArea(door, area)
                if (d > DOOR_REACH_PX) continue
                if (best == null || d < best.first) best = d to label
            }
        }
        return best?.second
    }

    private fun distanceToArea(p: PixelPoint, area: RoomArea): Double {
        val s = walls.scale
        val reach = (DOOR_REACH_PX / s).toInt() + 1
        val px = (p.x / s).toInt() - area.x0
        val py = (p.y / s).toInt() - area.y0
        var best = Double.MAX_VALUE
        for (y in maxOf(0, py - reach)..minOf(area.h - 1, py + reach)) {
            for (x in maxOf(0, px - reach)..minOf(area.w - 1, px + reach)) {
                if (!area.cells[y * area.w + x]) continue
                val d = hypot((x - px).toDouble(), (y - py).toDouble()) * s
                if (d < best) best = d
            }
        }
        return best
    }

    /**
     * Morphological opening that cuts off thin tails: a fill that slips through a narrow gap
     * into a strip next door (a closet, a doorway alcove) is joined to the room by a neck
     * narrower than the room itself. Shrinking by [TAIL_CELLS] breaks the neck, the piece
     * holding the tap is kept, and growing back inside the original fill restores its shape.
     */
    private fun trimTails(cells: BooleanArray, w: Int, h: Int, startCell: Int): BooleanArray {
        val r = TAIL_CELLS
        // Erosion as the complement of dilating the complement; outside the box counts as wall.
        val padded = BooleanArray((w + 2 * r) * (h + 2 * r)) { true }
        val pw = w + 2 * r
        for (y in 0 until h) {
            for (x in 0 until w) padded[(y + r) * pw + x + r] = !cells[y * w + x]
        }
        val grownGaps = WallGrid.dilate(padded, w + 2 * r, h + 2 * r, r)
        val core = BooleanArray(w * h) { i -> !grownGaps[(i / w + r) * (w + 2 * r) + i % w + r] }
        // Keep the core piece nearest the tap; a room thinner than 2r has no core, keep it whole.
        val seed = nearestTrue(core, w, h, startCell) ?: return cells
        val piece = BooleanArray(w * h)
        val queue = ArrayDeque<Int>().apply { add(seed) }
        piece[seed] = true
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            val x = c % w
            for (n in intArrayOf(c - 1, c + 1, c - w, c + w)) {
                if (n < 0 || n >= w * h) continue
                if ((n == c - 1 && x == 0) || (n == c + 1 && x == w - 1)) continue
                if (!piece[n] && core[n]) {
                    piece[n] = true
                    queue.addLast(n)
                }
            }
        }
        val grown = WallGrid.dilate(piece, w, h, r)
        return BooleanArray(w * h) { grown[it] && cells[it] }
    }

    private fun nearestTrue(cells: BooleanArray, w: Int, h: Int, from: Int): Int? {
        if (cells[from]) return from
        val fx = from % w
        val fy = from / w
        var best: Int? = null
        var bestD = Int.MAX_VALUE
        for (i in cells.indices) {
            if (!cells[i]) continue
            val dx = i % w - fx
            val dy = i / w - fy
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun nearestDoor(tap: PixelPoint, radius: Double): String? = doors
        .flatMap { (label, points) -> points.map { label to it.distanceTo(tap) } }
        .filter { it.second <= radius }
        .minByOrNull { it.second }
        ?.first

    private companion object {
        const val LABEL_SLACK_PX = 6.0
        const val DOOR_REACH_PX = 60.0
        const val OPEN_SEARCH_CELLS = 20

        /** Tails narrower than twice this many cells are cut off a fill. */
        const val TAIL_CELLS = 4
    }
}
