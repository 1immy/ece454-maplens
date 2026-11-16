package edu.wisc.maplens

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import edu.wisc.maplens.mapparse.ParseProgress
import edu.wisc.maplens.mapparse.ParseStage
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Colours for the parsing screen, which follows the phone's dark setting. */
internal data class ParsePalette(
    val background: Int,
    val ink: Int,
    val inkSecondary: Int,
    val track: Int,
    val accent: Int,
    /** The frost over the drawing, without alpha. */
    val veil: Int,
    /** Behind the drawn poster. */
    val wall: Int,
    val door: Int,
    /** The bar once the map is read. */
    val done: Int,
    val chip: Int,
    val buttonBorder: Int,
    /** True when the status bar needs dark icons. */
    val light: Boolean,
) {
    companion object {
        val LIGHT = ParsePalette(
            background = Color.rgb(246, 247, 249),
            ink = Color.rgb(15, 23, 42),
            inkSecondary = Color.rgb(71, 85, 105),
            track = Color.rgb(221, 225, 230),
            accent = POSTER_ACCENT,
            veil = Color.rgb(246, 247, 249),
            wall = Color.rgb(207, 200, 188),
            door = Color.rgb(176, 96, 0),
            done = Color.rgb(30, 142, 62),
            chip = Color.WHITE,
            buttonBorder = Color.rgb(226, 232, 240),
            light = true,
        )
        val DARK = ParsePalette(
            background = Color.rgb(13, 20, 32),
            ink = Color.rgb(232, 237, 244),
            inkSecondary = Color.rgb(163, 177, 196),
            track = Color.rgb(38, 51, 74),
            accent = Color.rgb(127, 178, 236),
            veil = Color.rgb(13, 20, 32),
            wall = Color.rgb(42, 44, 48),
            door = Color.rgb(242, 177, 76),
            done = Color.rgb(111, 207, 138),
            chip = Color.rgb(20, 29, 43),
            buttonBorder = Color.rgb(58, 74, 102),
            light = false,
        )

        /** The app's current look (see [AppTheme]). */
        fun current(): ParsePalette = if (Ui.dark) DARK else LIGHT
    }
}

/** Frost left over the drawing once the parse is [fraction] done: heavy at first, gone at 1. */
internal fun frostFor(fraction: Float): Float = (1f - fraction.coerceIn(0f, 1f)).pow(1.3f)

/**
 * How many of [count] room labels to show while the parse is at [progress]: none before it
 * reads rooms, then in step with the rooms it has read, and all of them once past reading.
 */
internal fun labelsShown(progress: ParseProgress, count: Int): Int = when {
    progress.stage < ParseStage.READ_ROOMS -> 0
    progress.stage > ParseStage.READ_ROOMS -> count
    progress.total <= 0 -> 0
    else -> (count * progress.done / progress.total).coerceIn(0, count)
}

/**
 * A drawn floor poster that resolves while the real parse runs: it settles flat after
 * straightening, the frost thins as the parse advances, corridors draw in while hallways are
 * traced, room labels pop in as rooms are read, then doors and START. It is an illustration,
 * not the user's map, but every step waits for the parser to really get there. The poster is
 * drawn once into two bitmaps, so a frame costs two bitmap draws and a few lines. With
 * animations turned off on the phone it steps instead.
 */
internal class ParseResolveView(context: Context) : View(context) {
    private var palette = ParsePalette.LIGHT
    private var sharp: Bitmap? = null
    private var soft: Bitmap? = null
    private val fit = Matrix()
    private val box = RectF()
    private val camera = Camera()
    private val tilt = Matrix()

    /** Start times (uptime ms) of each step; 0 until the parse reaches it. */
    private var flatAt = 0L
    private var corridorsAt = 0L
    private var doorsAt = 0L
    private var doneAt = 0L
    private val labelsAt = LongArray(LABELS.size)

    private var frost = 1f
    private var frostFrom = 1f
    private var frostTo = 1f
    private var frostAt = 0L
    private var rooms = 0
    private var onShown: (() -> Unit)? = null
    private val shownLater = Runnable { fireShown() }

    private val paper = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val veil = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val corridorPaths = CORRIDORS.map { (a, b) ->
        val path = Path()
        path.moveTo(a[0], a[1])
        path.lineTo(b[0], b[1])
        PathMeasure(path, false)
    }
    private val piece = Path()
    private val decel = DecelerateInterpolator(2f)
    private val pop = OvershootInterpolator(2.2f)

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(24))
            }
        }
        contentDescription = context.getString(R.string.poster_photo_description)
        usePalette(ParsePalette.LIGHT)
    }

    fun usePalette(look: ParsePalette) {
        palette = look
        setBackgroundColor(look.wall)
        invalidate()
    }

    /** Starts over: tilted, frosted, nothing found yet. */
    fun begin() {
        clear()
        frost = 1f
        frostFrom = 1f
        frostTo = 1f
        invalidate()
    }

    /** The parse reports [progress], [fraction] of the way through. */
    fun progress(progress: ParseProgress, fraction: Float) {
        val now = SystemClock.uptimeMillis()
        if (progress.stage > ParseStage.STRAIGHTEN && flatAt == 0L) flatAt = now
        if (progress.stage >= ParseStage.CORRIDORS && corridorsAt == 0L) {
            corridorsAt = now + if (flatAt == now) FLAT_MS / 3 else 0L
        }
        val labels = labelsShown(progress, LABELS.size)
        for (i in 0 until labels) if (labelsAt[i] == 0L) labelsAt[i] = now + i * LABEL_GAP_MS
        if (progress.stage >= ParseStage.PLACE_ROOMS && doorsAt == 0L) doorsAt = now
        frostTowards(frostFor(fraction), now)
        invalidate()
    }

    /** The map is read: everything lands, then [shown] runs (at once with animations off). */
    fun finish(rooms: Int, shown: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (flatAt == 0L) flatAt = now
        if (corridorsAt == 0L) corridorsAt = now
        for (i in labelsAt.indices) {
            if (labelsAt[i] == 0L) labelsAt[i] = now + i * LABEL_GAP_MS / 2
        }
        if (doorsAt == 0L) doorsAt = now
        doneAt = now
        this.rooms = rooms
        frostTowards(0f, now)
        onShown = shown
        invalidate()
        if (!animating()) {
            fireShown()
            return
        }
        val last = maxOf(
            corridorsAt + CORRIDOR_MS,
            labelsAt.max() + LABEL_MS,
            doorsAt + DOOR_MS,
        )
        postDelayed(shownLater, maxOf(last - now, PILL_MS) + HOLD_MS)
    }

    fun clear() {
        removeCallbacks(shownLater)
        onShown = null
        flatAt = 0L
        corridorsAt = 0L
        doorsAt = 0L
        doneAt = 0L
        labelsAt.fill(0L)
        frost = 0f
        frostFrom = 0f
        frostTo = 0f
        frostAt = 0L
    }

    private fun fireShown() {
        val run = onShown ?: return
        onShown = null
        run()
    }

    private fun animating() = ValueAnimator.areAnimatorsEnabled()

    private fun frostTowards(target: Float, now: Long) {
        if (target >= frostTo) return
        frostFrom = frost
        frostTo = target
        frostAt = now
    }

    /** 0 to 1 for a step that started at [at] and lasts [ms]; 1 when animations are off. */
    private fun share(at: Long, ms: Long, now: Long): Float {
        if (at == 0L || now < at) return 0f
        if (!animating()) return 1f
        return ((now - at).toFloat() / ms).coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = dp(20)
        val scale = min((w - 2 * pad) / POSTER_W, (h - 2 * pad - dp(48)) / POSTER_H)
        if (scale <= 0f) return
        val left = (w - POSTER_W * scale) / 2
        val top = (h - dp(48) - POSTER_H * scale) / 2
        box.set(left, top, left + POSTER_W * scale, top + POSTER_H * scale)
        fit.setScale(scale, scale)
        fit.postTranslate(left, top)
        sharp?.recycle()
        soft?.recycle()
        val drawn = Bitmap.createBitmap(
            (POSTER_W * scale).roundToInt().coerceAtLeast(1),
            (POSTER_H * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val poster = Canvas(drawn)
        poster.scale(scale, scale)
        drawPoster(poster)
        sharp = drawn
        // A tiny copy drawn large and filtered reads as a blur, and costs one bitmap draw.
        soft = Bitmap.createScaledBitmap(
            drawn,
            (drawn.width / SOFT_DIVISOR).coerceAtLeast(1),
            (drawn.height / SOFT_DIVISOR).coerceAtLeast(1),
            true,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val sharp = sharp ?: return
        val soft = soft ?: return
        val now = SystemClock.uptimeMillis()
        val frostShare = share(frostAt, FROST_MS, now)
        frost = frostFrom + (frostTo - frostFrom) * decel.getInterpolation(frostShare)
        val flat = decel.getInterpolation(share(flatAt, FLAT_MS, now))

        canvas.save()
        val cx = box.centerX()
        val cy = box.centerY()
        tilt.reset()
        camera.save()
        camera.rotateX(TILT_X * (1f - flat))
        camera.getMatrix(tilt)
        camera.restore()
        tilt.preTranslate(-cx, -cy)
        tilt.postTranslate(cx, cy)
        canvas.concat(tilt)
        val scale = TILT_SCALE + (1f - TILT_SCALE) * flat
        canvas.rotate(TILT_Z * (1f - flat), cx, cy)
        canvas.scale(scale, scale, cx, cy)

        paper.alpha = 255
        canvas.drawBitmap(soft, null, box, paper)
        paper.alpha = ((1f - frost) * 255).roundToInt()
        if (paper.alpha > 0) canvas.drawBitmap(sharp, null, box, paper)
        if (!palette.light) {
            veil.color = Color.argb(40, 0, 0, 0)
            canvas.drawRect(box, veil)
        }
        if (frost > 0f) {
            veil.color = palette.veil
            veil.alpha = (VEIL_ALPHA * frost).roundToInt()
            canvas.drawRect(box, veil)
        }

        canvas.concat(fit)
        drawBrackets(canvas, flat * (1f - share(doneAt, BRACKET_MS, now)))
        drawCorridors(canvas, now)
        drawDoors(canvas, now)
        drawLabels(canvas, now)
        canvas.restore()
        drawReady(canvas, now)

        if (animating() && stillMoving(now)) postInvalidateOnAnimation()
    }

    private fun stillMoving(now: Long): Boolean {
        fun running(at: Long, ms: Long) = at != 0L && now < at + ms
        return running(frostAt, FROST_MS) || running(flatAt, FLAT_MS) ||
            running(corridorsAt, CORRIDOR_MS) || running(doorsAt, DOOR_MS + RING_MS) ||
            running(doneAt, PILL_MS + BRACKET_MS) || labelsAt.any { running(it, LABEL_MS) }
    }

    private fun drawBrackets(canvas: Canvas, shown: Float) {
        if (shown <= 0f) return
        line.color = palette.accent
        line.alpha = (255 * shown).roundToInt()
        line.strokeWidth = 4f
        val l = 6f
        val t = 35f
        val r = 294f
        val b = 374f
        val arm = 19f
        piece.reset()
        piece.moveTo(l, t + arm)
        piece.lineTo(l, t)
        piece.lineTo(l + arm, t)
        piece.moveTo(r - arm, t)
        piece.lineTo(r, t)
        piece.lineTo(r, t + arm)
        piece.moveTo(r, b - arm)
        piece.lineTo(r, b)
        piece.lineTo(r - arm, b)
        piece.moveTo(l + arm, b)
        piece.lineTo(l, b)
        piece.lineTo(l, b - arm)
        canvas.drawPath(piece, line)
    }

    private fun drawCorridors(canvas: Canvas, now: Long) {
        if (corridorsAt == 0L) return
        line.color = palette.accent
        line.alpha = 255
        line.strokeWidth = 5f
        corridorPaths.forEachIndexed { i, measure ->
            val drawn = decel.getInterpolation(
                share(corridorsAt + i * CORRIDOR_GAP_MS, CORRIDOR_ONE_MS, now),
            )
            if (drawn <= 0f) return@forEachIndexed
            piece.reset()
            measure.getSegment(0f, measure.length * drawn, piece, true)
            canvas.drawPath(piece, line)
        }
    }

    private fun drawDoors(canvas: Canvas, now: Long) {
        if (doorsAt == 0L) return
        DOORS.forEachIndexed { i, (x, y) ->
            val size = pop.getInterpolation(share(doorsAt + i * DOOR_GAP_MS, DOOR_ONE_MS, now))
            if (size <= 0f) return@forEachIndexed
            fill.color = Color.WHITE
            canvas.drawCircle(x, y, 7f * size, fill)
            fill.color = palette.door
            canvas.drawCircle(x, y, 5f * size, fill)
        }
        val start = pop.getInterpolation(share(doorsAt + START_DELAY_MS, DOOR_ONE_MS, now))
        if (start <= 0f) return
        val ring = share(doorsAt + START_DELAY_MS, RING_MS, now)
        if (ring < 1f) {
            line.color = palette.accent
            line.alpha = ((1f - ring) * 230).roundToInt()
            line.strokeWidth = 3f
            canvas.drawCircle(START_X, START_Y, 8f + 14f * ring, line)
        }
        fill.color = Color.WHITE
        canvas.drawCircle(START_X, START_Y, 10.5f * start, fill)
        fill.color = palette.accent
        canvas.drawCircle(START_X, START_Y, 8f * start, fill)
    }

    private fun drawLabels(canvas: Canvas, now: Long) {
        text.textSize = 11f
        LABELS.forEachIndexed { i, (label, x, y) ->
            val shown = share(labelsAt[i], LABEL_MS, now)
            if (shown <= 0f) return@forEachIndexed
            val size = 0.8f + 0.2f * pop.getInterpolation(shown)
            val alpha = (255 * min(1f, shown * 2f)).roundToInt()
            canvas.save()
            canvas.translate(x, y - 3f + 6f * (1f - shown))
            canvas.scale(size, size)
            fill.color = Color.argb(alpha / 5, 15, 23, 42)
            canvas.drawRoundRect(-21f, -9f, 21f, 12f, 10f, 10f, fill)
            fill.color = palette.chip
            fill.alpha = alpha
            canvas.drawRoundRect(-21f, -10f, 21f, 10f, 10f, 10f, fill)
            line.color = palette.accent
            line.alpha = alpha
            line.strokeWidth = 1.5f
            canvas.drawRoundRect(-20.25f, -9.25f, 20.25f, 9.25f, 9.25f, 9.25f, line)
            text.color = palette.ink
            text.alpha = alpha
            canvas.drawText(label, 0f, 4f, text)
            canvas.restore()
        }
    }

    private fun drawReady(canvas: Canvas, now: Long) {
        val shown = decel.getInterpolation(share(doneAt, PILL_MS, now))
        if (shown <= 0f) return
        val label = resources.getQuantityString(R.plurals.poster_map_ready, rooms, rooms)
        text.textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            Ui.CAPTION,
            resources.displayMetrics,
        )
        val height = dp(36)
        val width = text.measureText(label) + dp(16) * 2 + dp(22)
        val top = this.height - dp(16) - height + dp(12) * (1f - shown)
        canvas.save()
        canvas.translate((this.width - width) / 2f, top)
        val alpha = (255 * shown).roundToInt()
        fill.color = READY_FILL
        fill.alpha = alpha
        canvas.drawRoundRect(0f, 0f, width, height, height / 2, height / 2, fill)
        line.color = Color.WHITE
        line.alpha = alpha
        line.strokeWidth = dp(2)
        val ix = dp(16)
        val iy = height / 2
        piece.reset()
        piece.moveTo(ix + dp(1), iy)
        piece.lineTo(ix + dp(5), iy + dp(4))
        piece.lineTo(ix + dp(12), iy - dp(4))
        canvas.drawPath(piece, line)
        text.color = Color.WHITE
        text.alpha = alpha
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, ix + dp(22), iy - (text.ascent() + text.descent()) / 2, text)
        text.textAlign = Paint.Align.CENTER
        canvas.restore()
    }

    /** The printed poster: paper, blue frame, rooms, orange routes, labels, the star. */
    private fun drawPoster(canvas: Canvas) {
        fill.color = Color.rgb(244, 240, 231)
        canvas.drawRoundRect(0f, 0f, POSTER_W, POSTER_H, 4f, 4f, fill)
        fill.color = FRAME
        canvas.drawRect(9f, 9f, 291f, 31f, fill)
        text.textSize = 10f
        text.color = Color.WHITE
        text.isFakeBoldText = true
        text.letterSpacing = 0.1f
        canvas.drawText("EVACUATION ROUTE · 3RD FLOOR", 150f, 24f, text)
        text.isFakeBoldText = false
        text.letterSpacing = 0f
        line.alpha = 255
        line.color = FRAME
        line.strokeWidth = 6f
        canvas.drawRect(9f, 38f, 291f, 371f, line)
        line.color = Color.rgb(107, 106, 102)
        line.strokeWidth = 2f
        for (room in ROOMS) canvas.drawRect(room[0], room[1], room[2], room[3], line)
        line.color = Color.rgb(232, 119, 46)
        line.strokeWidth = 4f
        for (route in ROUTES) {
            piece.reset()
            piece.moveTo(route[0], route[1])
            var i = 2
            while (i < route.size) {
                piece.lineTo(route[i], route[i + 1])
                i += 2
            }
            canvas.drawPath(piece, line)
        }
        text.textSize = 9f
        text.color = Color.rgb(61, 59, 55)
        for ((label, x, y) in LABELS) canvas.drawText(label, x, y, text)
        piece.reset()
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) 9f else 3.8f
            val a = Math.toRadians(-90.0 + k * 36.0)
            val px = START_X + r * cos(a).toFloat()
            val py = START_Y + r * sin(a).toFloat()
            if (k == 0) piece.moveTo(px, py) else piece.lineTo(px, py)
        }
        piece.close()
        fill.color = Color.rgb(224, 168, 0)
        canvas.drawPath(piece, fill)
        text.textSize = 7f
        text.isFakeBoldText = true
        canvas.drawText("YOU ARE HERE", START_X, START_Y + 24f, text)
        text.isFakeBoldText = false
    }

    private fun dp(value: Int) = value * resources.displayMetrics.density

    private companion object {
        const val POSTER_W = 300f
        const val POSTER_H = 380f
        const val START_X = 150f
        const val START_Y = 300f
        const val SOFT_DIVISOR = 14

        const val TILT_X = 14f
        const val TILT_Z = -5f
        const val TILT_SCALE = 0.9f
        const val VEIL_ALPHA = 150

        const val FLAT_MS = 850L
        const val FROST_MS = 700L
        const val CORRIDOR_ONE_MS = 650L
        const val CORRIDOR_GAP_MS = 110L
        const val CORRIDOR_MS = CORRIDOR_ONE_MS + 4 * CORRIDOR_GAP_MS
        const val LABEL_MS = 320L
        const val LABEL_GAP_MS = 120L
        const val DOOR_ONE_MS = 380L
        const val DOOR_GAP_MS = 60L
        const val START_DELAY_MS = 450L
        const val RING_MS = 1200L
        const val DOOR_MS = START_DELAY_MS + DOOR_ONE_MS
        const val BRACKET_MS = 300L
        const val PILL_MS = 360L

        /** How long "Map ready" stays before navigation opens. */
        const val HOLD_MS = 450L

        val FRAME = Color.rgb(43, 91, 168)

        /** Google's green 800: white text on it passes 4.5:1. */
        val READY_FILL = Color.rgb(19, 115, 51)

        val ROOMS = listOf(
            floatArrayOf(24f, 56f, 140f, 108f),
            floatArrayOf(160f, 56f, 276f, 108f),
            floatArrayOf(24f, 132f, 48f, 258f),
            floatArrayOf(72f, 132f, 138f, 258f),
            floatArrayOf(162f, 132f, 228f, 258f),
            floatArrayOf(252f, 132f, 276f, 258f),
            floatArrayOf(24f, 282f, 140f, 344f),
            floatArrayOf(160f, 282f, 276f, 344f),
        )
        val ROUTES = listOf(
            floatArrayOf(150f, 300f, 150f, 270f, 30f, 270f),
            floatArrayOf(150f, 270f, 150f, 120f, 270f, 120f),
            floatArrayOf(60f, 270f, 60f, 120f),
            floatArrayOf(240f, 270f, 240f, 120f),
        )
        val CORRIDORS = listOf(
            floatArrayOf(30f, 270f) to floatArrayOf(270f, 270f),
            floatArrayOf(150f, 330f) to floatArrayOf(150f, 60f),
            floatArrayOf(30f, 120f) to floatArrayOf(270f, 120f),
            floatArrayOf(60f, 270f) to floatArrayOf(60f, 120f),
            floatArrayOf(240f, 270f) to floatArrayOf(240f, 120f),
        )
        val DOORS = listOf(
            82f to 120f,
            218f to 120f,
            150f to 182f,
            150f to 214f,
            82f to 270f,
            218f to 270f,
            30f to 270f,
            270f to 120f,
        )
        val LABELS = listOf(
            Triple("3650", 82f, 86f),
            Triple("3654", 218f, 86f),
            Triple("3610", 105f, 198f),
            Triple("3614", 195f, 198f),
            Triple("3538", 82f, 317f),
            Triple("3444", 218f, 317f),
        )
    }
}
