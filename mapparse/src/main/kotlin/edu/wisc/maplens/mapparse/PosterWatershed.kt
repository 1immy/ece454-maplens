package edu.wisc.maplens.mapparse

import android.graphics.Rect
import edu.wisc.maplens.model.PixelPoint
import kotlin.math.abs
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Tolerances for [watershedDoors]. Pixels of the 2000 px straightened poster. */
internal data class WatershedParams(
    /** A meeting line this short to this long across open floor is a doorway. */
    val minDoorPx: Int = 3,
    val maxDoorPx: Int = 46,
    /** Distance from a wall that counts as fully open floor when ranking the flood. */
    val openFloorPx: Double = 30.0,
    /** A doorway with more real ink along it than this share is a symbol, not a door. */
    val maxInkShare: Double = 0.3,
    /** Furthest walk inside the corridor region from a doorway to a traced corridor. */
    val maxLinkPx: Int = 260,
    /** Shrink of a room number's box before it seeds the room (keeps seeds off walls). */
    val seedInset: Int = 2,
)

/**
 * Each room's door from a seeded watershed over open floor, the classical floor-plan room
 * segmentation (Bormann et al., "Room Segmentation: Survey, Implementation, and Analysis"):
 *
 * 1. Open floor is everything not wall; its distance transform is high in the middle of rooms
 *    and corridors and low at doorways and other bottlenecks.
 * 2. Seeds: each room's printed number, the traced corridors, and the poster's border for the
 *    outside. Every region grows from its seed, widest floor first, so neighbouring regions
 *    meet at the narrowest place between them.
 * 3. Where a room's region meets the corridor's across open floor, the meeting line is that
 *    room's opening: door-sized lines are doorways (the narrowest wins), a wider one is an
 *    open side (an approximate end), and none at all leaves the room to the caller.
 *
 * Untraced corridor floor joins the corridor region on its own, so a doorway onto it is still
 * found; the link returned walks the corridor region to the traced corridor.
 */
internal fun watershedDoors(
    walls: ByteArray,
    width: Int,
    rooms: List<PosterReading>,
    corridors: List<PixelPoint>,
    ink: ByteArray?,
    params: WatershedParams = WatershedParams(),
): List<DoorFinding?> = Mats().use { mats ->
    val height = walls.size / width
    val n = walls.size
    val timer = StageTimer()
    // Floor height for the flood: the distance to the nearest wall, inverted, so the widest
    // floor floods first and narrow gaps last. Walls flood after everything else.
    val free = mats.own(Mat(height, width, CvType.CV_8UC1))
    free.put(0, 0, ByteArray(n) { if (walls[it] != 0.toByte()) 0 else -1 })
    val dist = mats.mat()
    Imgproc.distanceTransform(free, dist, Imgproc.DIST_L2, 3)
    val distance = FloatArray(n).also { dist.get(0, 0, it) }
    val relief = ByteArray(n) { i ->
        if (walls[i] != 0.toByte()) {
            255.toByte()
        } else {
            val open = (distance[i] / params.openFloorPx).coerceIn(0.0, 1.0)
            (254 - (open * 250).toInt()).toByte()
        }
    }
    timer.mark("ws relief")
    // Seeds: 1 outside (the border), 2 corridor (traced lines), 3 + k room k (its number).
    val labels = IntArray(n)
    for (x in 0 until width) {
        labels[x] = OUTSIDE
        labels[(height - 1) * width + x] = OUTSIDE
    }
    for (y in 0 until height) {
        labels[y * width] = OUTSIDE
        labels[y * width + width - 1] = OUTSIDE
    }
    val line = BooleanArray(n)
    for (p in corridors) {
        val cx = p.x.toInt()
        val cy = p.y.toInt()
        for (dy in -1..1) for (dx in -1..1) {
            val x = cx + dx
            val y = cy + dy
            val i = y * width + x
            if (x in 1 until width - 1 && y in 1 until height - 1 && walls[i] == 0.toByte()) {
                labels[i] = CORRIDOR
                line[i] = true
            }
        }
    }
    for ((k, room) in rooms.withIndex()) {
        val box = Rect(room.box).apply { inset(params.seedInset, params.seedInset) }
        for (y in box.top.coerceAtLeast(1) until box.bottom.coerceAtMost(height - 1)) {
            for (x in box.left.coerceAtLeast(1) until box.right.coerceAtMost(width - 1)) {
                val i = y * width + x
                if (walls[i] == 0.toByte() && labels[i] != CORRIDOR) labels[i] = FIRST_ROOM + k
            }
        }
    }
    timer.mark("ws seeds")
    val region = priorityFlood(labels, relief, width, height)
    timer.mark("ws flood")
    ParseDebug.regions = region
    ParseDebug.relief = relief

    // Corridor floor actually joined to a traced corridor over open floor. Unnamed spaces (the
    // big unlabelled room in EH3's middle) end up in the corridor region through their walls
    // at the end of the flood; a room bordering one does not open onto the corridor there.
    val reach = BooleanArray(n)
    // Each reachable corridor cell's step back toward the nearest traced line (-1 on it), so a
    // doorway's way to the corridor is a walk down these pointers, not a search per room.
    val toward = IntArray(n) { -2 }
    run {
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) {
            if (line[i]) {
                reach[i] = true
                toward[i] = -1
                queue[tail++] = i
            }
        }
        val near = intArrayOf(-1, 1, -width, width)
        while (head < tail) {
            val i = queue[head++]
            for (d in near) {
                val j = i + d
                if (j !in 0 until n || reach[j]) continue
                if (region[j] != CORRIDOR || walls[j] != 0.toByte()) continue
                reach[j] = true
                toward[j] = i
                queue[tail++] = j
            }
        }
    }

    timer.mark("ws reach")
    // Meeting lines between each room and the reachable corridor, over open floor only: a
    // room's floor pixel touching corridor floor, or a contested pixel between the two.
    val meet = Array(rooms.size) { mutableListOf<Int>() }
    val around = intArrayOf(-1, 1, -width, width)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val i = y * width + x
            if (walls[i] != 0.toByte()) continue
            val own = region[i]
            if (own >= FIRST_ROOM) {
                val touches = around.any { d -> reach[i + d] }
                if (touches) meet[own - FIRST_ROOM] += i
            } else if (own == -1) {
                var room = 0
                var corridor = false
                for (d in around) {
                    val r = region[i + d]
                    if (reach[i + d]) corridor = true else if (r >= FIRST_ROOM) room = r
                }
                if (corridor && room >= FIRST_ROOM) meet[room - FIRST_ROOM] += i
            }
        }
    }

    timer.mark("ws meet")
    rooms.indices.toList().parallelMap { k ->
        doorOf(k, meet[k], region, reach, toward, width, ink, params)
    }.also { timer.mark("ws doors") }
}

private const val OUTSIDE = 1
private const val CORRIDOR = 2
private const val FIRST_ROOM = 3

/** Room [k]'s door from its meeting lines with the corridor, or null if it has none. */
private fun doorOf(
    k: Int,
    lines: List<Int>,
    region: IntArray,
    reach: BooleanArray,
    toward: IntArray,
    width: Int,
    ink: ByteArray?,
    params: WatershedParams,
): DoorFinding? {
    if (lines.isEmpty()) return null
    val inLine = lines.toHashSet()
    val seen = HashSet<Int>()
    data class Segment(val pixels: List<Int>, val span: Int, val inkShare: Double)
    val segments = mutableListOf<Segment>()
    for (start in lines) {
        if (!seen.add(start)) continue
        val queue = ArrayDeque<Int>().apply { add(start) }
        val pixels = mutableListOf<Int>()
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            pixels += i
            for (dy in -1..1) for (dx in -1..1) {
                val j = i + dy * width + dx
                if (j in inLine && seen.add(j)) queue.add(j)
            }
        }
        val xs = pixels.map { it % width }
        val ys = pixels.map { it / width }
        val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min()) + 1
        val inked = ink?.let { a ->
            pixels.count { a[it] != 0.toByte() }.toDouble() / pixels.size
        } ?: 0.0
        segments += Segment(pixels, span, inked)
    }
    // The flood meets at a doorway's narrowest cross-section, so a drawn door measures a few
    // pixels to a door's width here. The widest such contact is the doorway (a crack in a wall
    // is narrower); ink is only judged where there are enough pixels to judge it.
    val doors = segments.filter {
        it.span in params.minDoorPx..params.maxDoorPx &&
            (it.span < INK_MIN_SPAN || it.inkShare <= params.maxInkShare)
    }
    // A clear door-width opening beats a crack too narrow to judge (EH3 3540B: a 5 px crack
    // through a shared wall). Then doors face the corridor: the shortest walk to the traced
    // corridor wins (EH3 3556's other opening led into an unnamed stair lobby), the wider one
    // on a near tie.
    val chosen = doors.minWithOrNull(
        compareBy<Segment> { if (it.span < INK_MIN_SPAN) 1 else 0 }
            .thenBy { walkSteps(it.pixels, reach, toward, width) / TIE_STEPS }
            .thenByDescending { it.span },
    ) ?: segments.maxBy { it.span }
    val kind = if (chosen in doors) DoorKind.VERIFIED else DoorKind.APPROXIMATE
    val cx = chosen.pixels.sumOf { it % width } / chosen.pixels.size
    val cy = chosen.pixels.sumOf { it / width } / chosen.pixels.size
    val centre = chosen.pixels.minBy { p -> abs(p % width - cx) + abs(p / width - cy) }
    // The corridor side of the doorway, then a walk inside the corridor region to the line.
    val outside = (1..3).asSequence().flatMap { r ->
        sequenceOf(centre - r, centre + r, centre - r * width, centre + r * width)
    }.firstOrNull { it in region.indices && reach[it] } ?: return null
    val path = walkCorridor(outside, toward, width, params.maxLinkPx) ?: return null
    fun pt(i: Int) = PixelPoint((i % width).toDouble(), (i / width).toDouble())
    return DoorFinding(
        kind = kind,
        doorway = if (kind == DoorKind.VERIFIED) pt(centre) else null,
        outside = path.first(),
        corridor = path.last(),
        link = path,
        reason = "${segments.size} meeting lines, chose span ${chosen.span}, " +
            "ink ${"%.2f".format(chosen.inkShare)}",
    )
}

/** Walks to the corridor this close in length are a tie, broken by width. */
private const val TIE_STEPS = 15

/** Steps from an opening's corridor side down the [toward] pointers to the traced line. */
private fun walkSteps(pixels: List<Int>, reach: BooleanArray, toward: IntArray, width: Int): Int {
    var best = Int.MAX_VALUE
    for (p in pixels) {
        for (q in intArrayOf(p - 1, p + 1, p - width, p + width)) {
            if (q !in reach.indices || !reach[q]) continue
            var c = q
            var n = 0
            while (toward[c] >= 0 && n < MAX_WALK) {
                c = toward[c]
                n++
            }
            if (n < best) best = n
        }
    }
    return best
}

private const val MAX_WALK = 600

/** Contacts shorter than this are too few pixels to judge ink on. */
private const val INK_MIN_SPAN = 8

/** From [start] down the [toward] pointers to the traced line; null if it is too far. */
private fun walkCorridor(
    start: Int,
    toward: IntArray,
    width: Int,
    maxSteps: Int
): List<PixelPoint>? {
    val cells = mutableListOf(start)
    var c = start
    while (toward[c] >= 0) {
        c = toward[c]
        cells += c
        if (cells.size > maxSteps) return null
    }
    if (toward[c] != -1) return null
    val pts = cells.map { PixelPoint((it % width).toDouble(), (it / width).toDouble()) }
    if (pts.size == 1) return pts + pts
    // Every 4th cell is plenty for a link edge; the ends stay exact.
    return listOf(pts.first()) + pts.subList(1, pts.size - 1).filterIndexed { i, _ -> i % 4 == 3 } +
        pts.last()
}

/** Debug hooks for the on-phone probe: the last watershed's regions and flood relief. */
internal object ParseDebug {
    @Volatile var regions: IntArray? = null

    @Volatile var relief: ByteArray? = null
}

/**
 * Meyer's watershed flood on [relief] (0-255, low first) from the seeds in [seeds] (0 = not
 * yet labelled): each region grows to its unlabelled neighbours in order of relief, so regions
 * meet at the highest ground between them, here the narrowest floor. A pixel two regions reach
 * becomes part of the meeting line (-1). A 256-level bucket queue keeps it linear.
 *
 * (OpenCV's watershed ranks by colour difference between neighbours, not by height, so on
 * this relief it flooded straight through walls.)
 */
internal fun priorityFlood(seeds: IntArray, relief: ByteArray, width: Int, height: Int): IntArray {
    val n = seeds.size
    val label = seeds.copyOf()
    val queued = BooleanArray(n)
    val buckets = Array(256) { IntArrayList() }
    val near = IntArray(4)

    // Neighbours of [i] into [near]; returns how many (image edges have fewer).
    fun neighbours(i: Int): Int {
        val x = i % width
        var c = 0
        if (x > 0) near[c++] = i - 1
        if (x < width - 1) near[c++] = i + 1
        if (i >= width) near[c++] = i - width
        if (i < n - width) near[c++] = i + width
        return c
    }
    for (i in 0 until n) {
        if (label[i] <= 0) continue
        val c = neighbours(i)
        for (t in 0 until c) {
            val j = near[t]
            if (!queued[j] && label[j] == 0) {
                queued[j] = true
                buckets[relief[j].toInt() and 0xFF].add(j)
            }
        }
    }
    for (lv in 0 until 256) {
        val bucket = buckets[lv]
        var k = 0
        while (k < bucket.size) {
            val i = bucket[k++]
            val c = neighbours(i)
            var found = 0
            var clash = false
            for (t in 0 until c) {
                val l = label[near[t]]
                if (l > 0) {
                    if (found == 0) found = l else if (found != l) clash = true
                }
            }
            if (clash) {
                label[i] = -1
                continue
            }
            if (found == 0) continue
            label[i] = found
            for (t in 0 until c) {
                val j = near[t]
                if (!queued[j] && label[j] == 0) {
                    queued[j] = true
                    buckets[maxOf(relief[j].toInt() and 0xFF, lv)].add(j)
                }
            }
        }
        bucket.clear()
    }
    return label
}

/** A growable int list for the flood buckets, without boxing. */
internal class IntArrayList {
    private var data = IntArray(64)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]

    fun clear() {
        size = 0
        data = IntArray(64)
    }
}
