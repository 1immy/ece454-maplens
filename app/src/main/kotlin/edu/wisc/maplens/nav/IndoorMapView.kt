package edu.wisc.maplens.nav

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import edu.wisc.maplens.model.PixelPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A Google-Maps-style map of one floor: a muted floor plan under a camera that pans, zooms,
 * rotates and tilts, with a dotted walking route, a dropped pin and a blue position puck.
 *
 * The camera looks at map point ([cx], [cy]) at [zoom] screen px per map px, turned so that
 * map bearing [upRad] points up the screen, then tilted back by [tilt] degrees. One [Matrix]
 * maps map pixels to the screen (with perspective), and its inverse maps taps back.
 *
 * Follow modes lock the camera to the puck; [Follow.HEADING] also turns the map with the
 * user's heading and tilts it, as in turn-by-turn navigation. Any pan, pinch or twist by the
 * user drops back to [Follow.NONE] and calls [onUserMoved], so the screen can offer Re-center.
 *
 * All motion runs off one frame loop in [onDraw]: transitions ease over a fixed time, while
 * following and the puck use exponential smoothing so they look the same at any frame rate.
 * With system animations off, everything snaps instead.
 */
internal class IndoorMapView(context: Context) : View(context) {
    enum class Follow { NONE, POSITION, HEADING }

    // ---- Content ----

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            if (value != null && !placed) {
                placed = true
                openingFit = true
                post { if (openingFit) fitWhole(animate = false) }
            }
            invalidate()
        }

    /** True shows the poster's own colors; false a calm grey plan so the route stands out. */
    var posterColors = false
        set(value) {
            field = value
            imagePaint.colorFilter = if (value) null else MUTED
            invalidate()
        }

    /** Walking route in map px, START first. */
    var route: List<PixelPoint> = emptyList()
        set(value) {
            field = value
            routeDistances = value.indices.runningFold(0.0) { acc, i ->
                if (i == 0) acc else acc + value[i - 1].distanceTo(value[i])
            }.drop(1)
            invalidate()
        }

    /** Distance already walked along [route], drawn grey. */
    var routeWalkedPx = 0.0
        set(value) {
            field = value
            invalidate()
        }

    /** A red pin: the selected place or the destination. */
    var pin: PixelPoint? = null
        set(value) {
            if (value != null && value != field) pinDropStart = SystemClock.uptimeMillis()
            field = value
            invalidate()
        }
    var pinLabel: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The room picked on the map, tinted like a selected place. */
    var highlight: RoomArea? = null
        set(value) {
            field = value
            highlightMask = value?.let(::maskOf)
            invalidate()
        }
    private var highlightMask: Bitmap? = null
    private var highlightLeft = 0f
    private var highlightTop = 0f
    private var highlightScale = 1f

    /** Map pixels covered by [screenPx] screen pixels at the current zoom. */
    fun mapDistance(screenPx: Float): Double = screenPx / zoom

    /** The area grown back to its walls, as a soft-edged tint bitmap in grid cells. */
    private fun maskOf(area: RoomArea): Bitmap {
        val pad = area.pad
        val w = area.w + 2 * pad
        val h = area.h + 2 * pad
        val cells = BooleanArray(w * h)
        for (y in 0 until area.h) {
            for (x in 0 until area.w) {
                cells[(y + pad) * w + x + pad] = area.cells[y * area.w + x]
            }
        }
        val grown = WallGrid.dilate(cells, w, h, pad)
        val colors = IntArray(w * h) { if (grown[it]) HIGHLIGHT else 0 }
        highlightLeft = ((area.x0 - pad) * area.scale).toFloat()
        highlightTop = ((area.y0 - pad) * area.scale).toFloat()
        highlightScale = area.scale.toFloat()
        return Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Ground-truth taps from checkpoints, drawn as small orange dots. */
    var checkpoints: List<PixelPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Map bearings of the corridors at START, drawn as tappable arrows around the puck. */
    var facingChoices: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var onFacingTap: ((Int) -> Unit)? = null

    /** Turn-by-turn mode: the puck becomes a navigation arrow. */
    var navigating = false
        set(value) {
            field = value
            invalidate()
        }

    var onMapTap: ((PixelPoint) -> Unit)? = null
    var onUserMoved: (() -> Unit)? = null

    /** Called every frame the camera turns, so a compass can follow. */
    var onRotationChanged: ((Float) -> Unit)? = null

    // ---- Camera ----

    private var cx = 0.0
    private var cy = 0.0
    private var zoom = 1.0
    private var upRad = MAP_UP
    private var tilt = 0.0

    /** Where the camera centre sits down the visible area: ½, or lower while navigating. */
    private var anchorShare = 0.5

    var follow = Follow.NONE
        set(value) {
            field = value
            invalidate()
        }

    /** Tilt and zoom (×whole-map fit) the camera eases to while following the heading. */
    private val navTilt = 40.0
    private val navZoom = 4.2

    /** Screen area hidden by floating panels; the camera centres in what is left. */
    private var insetTop = 0
    private var insetBottom = 0

    /** Rotation of the map on screen in degrees, for a compass needle. */
    val rotationDeg: Float get() = NavMath.screenRotationDeg(upRad).toFloat()

    // ---- Puck ----

    private var puckFrom: PixelPoint? = null
    private var puckTo: PixelPoint? = null
    private var puckStart = 0L
    private var puckDuration = 0L
    private var puckLinear = false
    private var puckX = 0.0
    private var puckY = 0.0
    private var headingTarget: Double? = null
    private var headingShown: Double? = null
    private var accuracyTarget = 0.0
    private var accuracyShown = 0.0

    val hasPuck: Boolean get() = puckTo != null

    // ---- Animation state ----

    private var placed = false

    /** True until the camera is first moved on purpose; until then the floor is re-fitted. */
    private var openingFit = false
    private var transition: Transition? = null
    private var lastFrame = 0L
    private var pinDropStart = 0L

    private class Transition(
        val start: Long,
        val duration: Long,
        val from: DoubleArray,
        val to: DoubleArray,
    )

    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val overshoot = OvershootInterpolator(2.2f)

    // ---- Drawing ----

    private val density = resources.displayMetrics.density
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val tiltCamera = Camera()
    private val tiltMatrix = Matrix()
    private val pt = FloatArray(2)
    private var routeDistances: List<Double> = emptyList()

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = MUTED
    }
    private val dotBorder = fill(Color.WHITE)
    private val dotBlue = fill(ROUTE_BLUE)
    private val dotWalked = fill(Color.rgb(154, 160, 166))
    private val shadow = fill(Color.argb(46, 0, 0, 0))
    private val puckWhite = fill(Color.WHITE)
    private val puckBlue = fill(PUCK_BLUE)
    private val accuracyFill = fill(Color.argb(38, 26, 115, 232))
    private val accuracyRing = stroke(Color.argb(90, 26, 115, 232), 1f)
    private val beamPaint = fill(Color.WHITE)
    private val arrowStroke = stroke(Color.WHITE, 2.5f)
    private val pinRed = fill(PIN_RED)
    private val pinDark = fill(Color.rgb(165, 14, 14))
    private val labelHalo = stroke(Color.WHITE, 3.5f).apply {
        textSize = 14 * density
        typeface = MEDIUM
        textAlign = Paint.Align.CENTER
    }
    private val labelText = fill(Color.rgb(165, 14, 14)).apply {
        textSize = 14 * density
        typeface = MEDIUM
        textAlign = Paint.Align.CENTER
    }
    private val highlightPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val checkpointPaint = fill(Color.rgb(239, 108, 0))
    private val chipPaint = fill(Color.WHITE)
    private val chipArrow = fill(ROUTE_BLUE)
    private val chipLine = stroke(Color.argb(150, 26, 115, 232), 2f)
    private val shape = Path()
    private val box = RectF()
    private var beamShader: RadialGradient? = null
    private var beamRadius = 0f

    init {
        tiltCamera.setLocation(0f, 0f, -18f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ---- Public camera API ----

    /**
     * Panels opening or closing must not drag the map along: unless the camera is following
     * or mid-move, the camera centre shifts with the new anchor so nothing moves on screen.
     */
    fun setInsets(top: Int, bottom: Int) {
        if (top == insetTop && bottom == insetBottom) return
        if (openingFit) {
            // Panels laid out after the first fit: fit again so the floor sits between them.
            insetTop = top
            insetBottom = bottom
            post { if (openingFit) fitWhole(animate = false) }
            return
        }
        val still = follow == Follow.NONE && transition == null && width > 0 && bitmap != null
        val newAnchorY = top + (height - top - bottom).toFloat().coerceAtLeast(1f) *
            anchorShare.toFloat()
        val keep = if (still) toMap(anchorX(), newAnchorY) else null
        insetTop = top
        insetBottom = bottom
        if (keep != null) {
            cx = keep.x
            cy = keep.y
        }
        invalidate()
    }

    /** The whole floor, north (poster) up, flat. */
    fun showAll(animate: Boolean = true) {
        openingFit = false
        fitWhole(animate)
    }

    private fun fitWhole(animate: Boolean) {
        val image = bitmap ?: return
        follow = Follow.NONE
        moveTo(image.width / 2.0, image.height / 2.0, fitZoom(), MAP_UP, 0.0, animate)
    }

    /** Frames [points] with a margin, keeping the current rotation unless [northUp]. */
    fun fit(points: List<PixelPoint>, northUp: Boolean = true, animate: Boolean = true) {
        openingFit = false
        if (points.isEmpty() || width == 0) return
        follow = Follow.NONE
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val margin = 56 * density
        val w = (width - 2 * margin).coerceAtLeast(1f)
        val h = (visibleHeight() - 2 * margin).coerceAtLeast(1f)
        val z = min(w / (maxX - minX).coerceAtLeast(1.0), h / (maxY - minY).coerceAtLeast(1.0))
            .coerceIn(minZoom(), fitZoom() * 3.5)
        val up = if (northUp) MAP_UP else upRad
        moveTo((minX + maxX) / 2, (minY + maxY) / 2, z, up, 0.0, animate)
    }

    /** Centers on one point at a street-level zoom; [northUp] also turns the map poster-up. */
    fun focus(point: PixelPoint, northUp: Boolean = false, animate: Boolean = true) {
        openingFit = false
        follow = Follow.NONE
        val up = if (northUp) MAP_UP else upRad
        val t = if (northUp) 0.0 else tilt
        moveTo(point.x, point.y, fitZoom() * 3.0, up, t, animate)
    }

    /** Back to poster-up and flat, keeping the centre. */
    fun resetNorth() {
        openingFit = false
        if (follow == Follow.HEADING) follow = Follow.POSITION
        moveTo(cx, cy, zoom, MAP_UP, 0.0, animate = true)
    }

    /** Starts following the puck, easing in from wherever the camera is now. */
    fun startFollowing(mode: Follow) {
        openingFit = false
        follow = mode
        val z = if (mode == Follow.HEADING) {
            fitZoom() * navZoom
        } else {
            zoom.coerceAtLeast(
                fitZoom() * 2.5
            )
        }
        val t = if (mode == Follow.HEADING) navTilt else 0.0
        val up = if (mode == Follow.HEADING) headingShown ?: upRad else upRad
        moveTo(puckX, puckY, z, up, t, animate = true, durationMs = 900)
    }

    /** Flattens the map over the puck for tapping a position, keeping zoom and rotation. */
    fun flatten() {
        openingFit = false
        follow = Follow.NONE
        val x = if (hasPuck) puckX else cx
        val y = if (hasPuck) puckY else cy
        moveTo(x, y, zoom, upRad, 0.0, animate = true)
    }

    // ---- Public puck API ----

    /**
     * Moves the puck to [position] over [durationMs]; [linear] for a steady walk (demo steps
     * at a fixed cadence), otherwise it decelerates into place like a GPS fix.
     */
    fun setPuck(
        position: PixelPoint?,
        accuracyPx: Double = 0.0,
        durationMs: Long = 350,
        linear: Boolean = false
    ) {
        if (position == null) {
            puckFrom = null
            puckTo = null
            invalidate()
            return
        }
        val first = puckTo == null
        puckFrom = if (first) position else PixelPoint(puckX, puckY)
        puckTo = position
        puckStart = SystemClock.uptimeMillis()
        puckDuration = if (first || !animationsOn()) 0 else durationMs
        puckLinear = linear
        if (first) {
            puckX = position.x
            puckY = position.y
        }
        accuracyTarget = accuracyPx
        invalidate()
    }

    /** The direction the user faces, or null to hide the beam. */
    fun setHeading(rad: Double?) {
        headingTarget = rad
        if (rad == null) headingShown = null else if (headingShown == null) headingShown = rad
        invalidate()
    }

    // ---- Frame loop ----

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016 else ((now - lastFrame) / 1000.0).coerceIn(0.0, 0.05)
        lastFrame = now
        var moving = advance(now, dt)

        canvas.drawColor(BACKGROUND)
        val image = bitmap ?: return
        buildMatrix()
        canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(image, 0f, 0f, imagePaint)
        highlightMask?.let { mask ->
            canvas.translate(highlightLeft, highlightTop)
            canvas.scale(highlightScale, highlightScale)
            canvas.drawBitmap(mask, 0f, 0f, highlightPaint)
        }
        canvas.restore()

        drawRoute(canvas)
        for (truth in checkpoints) {
            val (x, y) = screen(truth.x, truth.y)
            canvas.drawCircle(x, y, 5 * density, checkpointPaint)
        }
        if (hasPuck) {
            if (facingChoices.isNotEmpty()) drawFacing(canvas)
            drawPuck(canvas)
        }
        pin?.let { moving = drawPin(canvas, it, now) || moving }

        onRotationChanged?.invoke(rotationDeg)
        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }

    /** Steps every animation; true while anything still moves. */
    private fun advance(now: Long, dt: Double): Boolean {
        var moving = false
        val snap = !animationsOn()

        // Puck position along its current segment.
        val from = puckFrom
        val to = puckTo
        if (from != null && to != null) {
            val raw = if (puckDuration <= 0) {
                1f
            } else {
                ((now - puckStart).toFloat() / puckDuration).coerceIn(
                    0f,
                    1f
                )
            }
            val t = if (puckLinear) raw else DECELERATE.getInterpolation(raw)
            puckX = from.x + (to.x - from.x) * t
            puckY = from.y + (to.y - from.y) * t
            if (raw < 1f) moving = true
        }
        val target = headingTarget
        val shown = headingShown
        if (target != null && shown != null) {
            val next = if (snap) {
                target
            } else {
                NavMath.lerpAngle(
                    shown,
                    target,
                    NavMath.approach(dt, 0.08)
                )
            }
            if (abs(NavMath.wrap(target - next)) > 1e-3) moving = true
            headingShown = next
        }
        val a = if (snap) 1.0 else NavMath.approach(dt, 0.3)
        accuracyShown += (accuracyTarget - accuracyShown) * a
        if (abs(accuracyTarget - accuracyShown) > 0.3) moving = true

        // Camera: an eased transition, possibly retargeted at the puck while following.
        val tr = transition
        if (tr != null) {
            val raw = ((now - tr.start).toFloat() / tr.duration).coerceIn(0f, 1f)
            val t = ease.getInterpolation(raw).toDouble()
            val goal = tr.to.copyOf()
            if (follow != Follow.NONE && hasPuck) {
                goal[0] = puckX
                goal[1] = puckY
                if (follow == Follow.HEADING) headingShown?.let { goal[3] = it }
            }
            cx = tr.from[0] + (goal[0] - tr.from[0]) * t
            cy = tr.from[1] + (goal[1] - tr.from[1]) * t
            zoom = tr.from[2] * (goal[2] / tr.from[2]).pow(t)
            upRad = NavMath.lerpAngle(tr.from[3], goal[3], t)
            tilt = tr.from[4] + (goal[4] - tr.from[4]) * t
            anchorShare = tr.from[5] + (goal[5] - tr.from[5]) * t
            if (raw >= 1f) transition = null
            moving = true
        } else if (follow != Follow.NONE && hasPuck) {
            cx = puckX
            cy = puckY
            if (follow == Follow.HEADING) {
                headingShown?.let { h ->
                    val next = if (snap) {
                        h
                    } else {
                        NavMath.lerpAngle(
                            upRad,
                            h,
                            NavMath.approach(dt, 0.35)
                        )
                    }
                    if (abs(NavMath.wrap(h - next)) > 1e-3) moving = true
                    upRad = next
                }
            }
        }
        return moving
    }

    private fun moveTo(
        x: Double,
        y: Double,
        z: Double,
        up: Double,
        t: Double,
        animate: Boolean,
        durationMs: Long = 650,
    ) {
        val zz = z.coerceIn(minZoom(), maxZoom())
        val share = if (follow == Follow.HEADING) NAV_ANCHOR else 0.5
        if (!animate || !animationsOn() || width == 0) {
            transition = null
            cx = x
            cy = y
            zoom = zz
            upRad = up
            tilt = t
            anchorShare = share
            invalidate()
            return
        }
        transition = Transition(
            SystemClock.uptimeMillis(),
            durationMs,
            doubleArrayOf(cx, cy, zoom, upRad, tilt, anchorShare),
            doubleArrayOf(x, y, zz, up, t, share),
        )
        invalidate()
    }

    private fun animationsOn() = ValueAnimator.areAnimatorsEnabled()

    // ---- Geometry ----

    private fun visibleHeight() = (height - insetTop - insetBottom).toFloat().coerceAtLeast(1f)

    private fun anchorX() = width / 2f

    /** Following the heading, the puck sits low so more of the way ahead shows. */
    private fun anchorY(): Float = insetTop + visibleHeight() * anchorShare.toFloat()

    private fun fitZoom(): Double {
        val image = bitmap ?: return 1.0
        return min(width / image.width.toDouble(), visibleHeight() / image.height.toDouble())
    }

    private fun minZoom() = fitZoom() * 0.7

    private fun maxZoom() = fitZoom() * 9.0

    private fun buildMatrix() {
        matrix.reset()
        matrix.postTranslate(-cx.toFloat(), -cy.toFloat())
        matrix.postRotate(NavMath.screenRotationDeg(upRad).toFloat())
        matrix.postScale(zoom.toFloat(), zoom.toFloat())
        if (tilt > 0.01) {
            tiltCamera.save()
            tiltCamera.rotateX(tilt.toFloat())
            tiltCamera.getMatrix(tiltMatrix)
            tiltCamera.restore()
            matrix.postConcat(tiltMatrix)
        }
        matrix.postTranslate(anchorX(), anchorY())
        matrix.invert(inverse)
    }

    private fun screen(x: Double, y: Double): Pair<Float, Float> {
        pt[0] = x.toFloat()
        pt[1] = y.toFloat()
        matrix.mapPoints(pt)
        return pt[0] to pt[1]
    }

    private fun toMap(x: Float, y: Float): PixelPoint {
        buildMatrix()
        pt[0] = x
        pt[1] = y
        inverse.mapPoints(pt)
        return PixelPoint(pt[0].toDouble(), pt[1].toDouble())
    }

    /** Screen angle of map bearing [rad] at map point ([x], [y]); honours rotation and tilt. */
    private fun screenAngle(x: Double, y: Double, rad: Double): Float {
        val (x0, y0) = screen(x, y)
        val (x1, y1) = screen(x + 20 * cos(rad), y + 20 * sin(rad))
        return atan2(y1 - y0, x1 - x0)
    }

    // ---- Layers ----

    private fun drawRoute(canvas: Canvas) {
        if (route.size < 2) return
        val total = routeDistances.last()
        // Dots sit at fixed map distances so they don't crawl as the walker moves; the spacing
        // steps by powers of two with zoom so they never crowd or spread too far.
        val wanted = 13 * density / zoom
        val spacing = 2.0.pow((ln(wanted) / ln(2.0)).roundToInt().toDouble())
        val r = 4.2f * density
        var s = 0.0
        var segment = 0
        while (s <= total) {
            while (segment < route.lastIndex - 1 && routeDistances[segment + 1] < s) segment++
            val a = route[segment]
            val b = route[segment + 1]
            val len = routeDistances[segment + 1] - routeDistances[segment]
            val t = if (len <= 0) 0.0 else (s - routeDistances[segment]) / len
            val (x, y) = screen(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            if (x > -r && y > -r && x < width + r && y < height + r) {
                if (s < routeWalkedPx) {
                    canvas.drawCircle(x, y, r * 0.75f, dotWalked)
                } else {
                    canvas.drawCircle(x, y, r + 1.6f * density, dotBorder)
                    canvas.drawCircle(x, y, r, dotBlue)
                }
            }
            s += spacing
        }
    }

    private fun drawPuck(canvas: Canvas) {
        val (x, y) = screen(puckX, puckY)
        val acc = (accuracyShown * zoom).toFloat()
        if (acc > 14 * density) {
            canvas.drawCircle(x, y, acc, accuracyFill)
            canvas.drawCircle(x, y, acc, accuracyRing)
        }
        val heading = headingShown
        if (navigating && heading != null) {
            drawChevron(canvas, x, y, screenAngle(puckX, puckY, heading))
            return
        }
        if (heading != null) drawBeam(canvas, x, y, screenAngle(puckX, puckY, heading))
        canvas.drawCircle(x, y + density, 11.5f * density, shadow)
        canvas.drawCircle(x, y, 11f * density, puckWhite)
        canvas.drawCircle(x, y, 7.5f * density, puckBlue)
    }

    /** The soft cone that shows which way the phone faces. */
    private fun drawBeam(canvas: Canvas, x: Float, y: Float, angle: Float) {
        val radius = 64 * density
        if (beamShader == null || beamRadius != radius) {
            beamRadius = radius
            beamShader = RadialGradient(
                0f,
                0f,
                radius,
                intArrayOf(Color.argb(150, 26, 115, 232), Color.argb(0, 26, 115, 232)),
                floatArrayOf(0.15f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        box.set(-radius, -radius, radius, radius)
        shape.reset()
        shape.moveTo(0f, 0f)
        shape.arcTo(box, -BEAM_HALF_DEG, 2 * BEAM_HALF_DEG)
        shape.close()
        beamPaint.shader = beamShader
        canvas.drawPath(shape, beamPaint)
        canvas.restore()
    }

    /** Turn-by-turn puck: a blue arrowhead with a white edge, pointing the way the user faces. */
    private fun drawChevron(canvas: Canvas, x: Float, y: Float, angle: Float) {
        val u = density
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f)
        shape.reset()
        shape.moveTo(0f, -17 * u)
        shape.lineTo(13 * u, 14 * u)
        shape.lineTo(0f, 7 * u)
        shape.lineTo(-13 * u, 14 * u)
        shape.close()
        canvas.translate(0f, 1.5f * u)
        canvas.drawPath(shape, shadow)
        canvas.translate(0f, -1.5f * u)
        canvas.drawPath(shape, arrowStroke)
        canvas.drawPath(shape, puckBlue)
        canvas.restore()
    }

    /** "Which way are you facing?": one arrow per corridor, a short way out from the puck. */
    private fun drawFacing(canvas: Canvas) {
        val (x, y) = screen(puckX, puckY)
        for (bearing in facingChoices) {
            val angle = screenAngle(puckX, puckY, bearing)
            val (bx, by) = facingButton(x, y, angle)
            canvas.drawLine(
                x + 16 * density * cos(angle),
                y + 16 * density * sin(angle),
                bx - FACING_RADIUS * density * cos(angle),
                by - FACING_RADIUS * density * sin(angle),
                chipLine,
            )
            canvas.drawCircle(bx, by + density, (FACING_RADIUS + 1) * density, shadow)
            canvas.drawCircle(bx, by, FACING_RADIUS * density, chipPaint)
            canvas.save()
            canvas.translate(bx, by)
            canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f)
            val u = density
            shape.reset()
            shape.moveTo(0f, -10 * u)
            shape.lineTo(8 * u, 2 * u)
            shape.lineTo(3 * u, 2 * u)
            shape.lineTo(3 * u, 10 * u)
            shape.lineTo(-3 * u, 10 * u)
            shape.lineTo(-3 * u, 2 * u)
            shape.lineTo(-8 * u, 2 * u)
            shape.close()
            canvas.drawPath(shape, chipArrow)
            canvas.restore()
        }
    }

    private fun facingButton(x: Float, y: Float, angle: Float): Pair<Float, Float> {
        val d = FACING_DISTANCE * density
        return (x + d * cos(angle)) to (y + d * sin(angle))
    }

    /** Teardrop pin with its tip on [at]; it drops in with a small bounce. Returns true while animating. */
    private fun drawPin(canvas: Canvas, at: PixelPoint, now: Long): Boolean {
        val (x, tipY) = screen(at.x, at.y)
        val u = density
        val elapsed = (now - pinDropStart).toFloat()
        val raw = if (animationsOn()) (elapsed / PIN_DROP_MS).coerceIn(0f, 1f) else 1f
        val lift = (1f - overshoot.getInterpolation(raw)) * 36 * u
        val y = tipY - lift
        canvas.drawOval(x - 5 * u, tipY - 2 * u, x + 5 * u, tipY + 2 * u, shadow)
        val r = 12 * u
        val cyPin = y - 26 * u
        shape.reset()
        shape.moveTo(x, y)
        shape.cubicTo(x - 4 * u, y - 9 * u, x - r, cyPin + 6 * u, x - r, cyPin)
        box.set(x - r, cyPin - r, x + r, cyPin + r)
        shape.arcTo(box, 180f, 180f)
        shape.cubicTo(x + r, cyPin + 6 * u, x + 4 * u, y - 9 * u, x, y)
        shape.close()
        canvas.drawPath(shape, pinRed)
        canvas.drawCircle(x, cyPin, 4.5f * u, pinDark)
        pinLabel?.let { label ->
            val base = tipY + 20 * u
            canvas.drawText(label, x, base, labelHalo)
            canvas.drawText(label, x, base, labelText)
        }
        return raw < 1f
    }

    // ---- Gestures ----

    private var lastAngle: Float? = null
    private var twist = 0f
    private var rotating = false

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userMoved()
                keepUnder(detector.focusX, detector.focusY) {
                    zoom = (zoom * detector.scaleFactor).coerceIn(minZoom(), maxZoom())
                }
                return true
            }
        },
    )

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float,
            ): Boolean {
                if (e2.pointerCount > 1) return true
                userMoved()
                val p = toMap(anchorX() + dx, anchorY() + dy)
                cx = p.x
                cy = p.y
                clampCenter()
                invalidate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (hasPuck && facingChoices.isNotEmpty()) {
                    buildMatrix()
                    val (x, y) = screen(puckX, puckY)
                    facingChoices.forEachIndexed { i, bearing ->
                        val (bx, by) = facingButton(x, y, screenAngle(puckX, puckY, bearing))
                        if (hypot(e.x - bx, e.y - by) < (FACING_RADIUS + 10) * density) {
                            onFacingTap?.invoke(i)
                            return true
                        }
                    }
                }
                val callback = onMapTap ?: return false
                callback(toMap(e.x, e.y))
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                userMoved()
                val p = toMap(e.x, e.y)
                val z = (zoom * 2).coerceAtMost(maxZoom())
                val f = zoom / z
                moveTo(
                    p.x + (cx - p.x) * f,
                    p.y + (cy - p.y) * f,
                    z,
                    upRad,
                    tilt,
                    animate = true,
                    durationMs = 350
                )
                return true
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            transition = null
            // Once the user touches the map, panels opening must not re-fit it any more.
            openingFit = false
        }
        scaler.onTouchEvent(event)
        handleTwist(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    /** Two-finger rotation, only after a deliberate twist so pinching doesn't turn the map. */
    private fun handleTwist(event: MotionEvent) {
        if (event.pointerCount < 2 || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            lastAngle = null
            if (event.pointerCount < 2) {
                twist = 0f
                rotating = false
            }
            return
        }
        val angle = atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
        val previous = lastAngle
        lastAngle = angle
        if (previous == null) return
        val delta = NavMath.wrap((angle - previous).toDouble()).toFloat()
        if (!rotating) {
            twist += delta
            if (abs(twist) < TWIST_START_RAD) return
            rotating = true
        }
        userMoved()
        val fx = (event.getX(0) + event.getX(1)) / 2
        val fy = (event.getY(0) + event.getY(1)) / 2
        keepUnder(fx, fy) { upRad = NavMath.wrap(upRad - delta) }
    }

    /** Applies [change] while keeping the map point under screen ([sx], [sy]) where it is. */
    private inline fun keepUnder(sx: Float, sy: Float, change: () -> Unit) {
        val before = toMap(sx, sy)
        change()
        val after = toMap(sx, sy)
        cx += before.x - after.x
        cy += before.y - after.y
        clampCenter()
        invalidate()
    }

    private fun clampCenter() {
        val image = bitmap ?: return
        cx = cx.coerceIn(0.0, image.width.toDouble())
        cy = cy.coerceIn(0.0, image.height.toDouble())
    }

    private fun userMoved() {
        openingFit = false
        transition = null
        if (follow != Follow.NONE) {
            follow = Follow.NONE
            onUserMoved?.invoke()
        }
    }

    private fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    companion object {
        /** Map bearing of "up" on the poster (image y grows downward). */
        const val MAP_UP = -PI / 2

        val ROUTE_BLUE = Color.rgb(26, 115, 232)
        val PUCK_BLUE = Color.rgb(26, 115, 232)
        val PIN_RED = Color.rgb(234, 67, 53)
        val BACKGROUND = Color.rgb(232, 234, 237)

        private const val HIGHLIGHT = 0x40EA4335
        private const val NAV_ANCHOR = 0.70
        private const val BEAM_HALF_DEG = 34f
        private const val FACING_DISTANCE = 78f
        private const val FACING_RADIUS = 20f
        private const val PIN_DROP_MS = 420f
        private const val TWIST_START_RAD = 0.17f

        private val DECELERATE = PathInterpolator(0f, 0f, 0.2f, 1f)
        private val MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        /**
         * Greyscale with lifted blacks: walls read as soft grey lines on near-white, so the
         * blue route and puck carry the colour, like a base map.
         */
        private val MUTED = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0f)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            0.72f, 0f, 0f, 0f, 74f,
                            0f, 0.72f, 0f, 0f, 76f,
                            0f, 0f, 0.72f, 0f, 80f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            },
        )
    }
}
