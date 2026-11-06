package edu.wisc.maplens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import edu.wisc.maplens.model.FloorGraph
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The straightened map with the route, START, destination and position dot drawn on top.
 * Pinch to zoom, drag to pan, double-tap to show the whole map. Everything is drawn from
 * map-pixel coordinates through one [Matrix], so the overlay always lines up with the image.
 */
internal val REVIEW_CORRIDOR = Color.rgb(151, 45, 150)
internal val REVIEW_ROOM = Color.rgb(17, 115, 59)
internal val REVIEW_CHECK = Color.rgb(160, 78, 8)
internal val REVIEW_START = Color.rgb(46, 125, 50)

class MapView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            showAll()
        }

    var start: PixelPoint? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The draft being checked: its graph, its rooms and the one picked, if any. */
    internal var review: FloorGraph? = null
        set(value) {
            field = value
            invalidate()
        }
    internal var reviewRooms: List<ReviewRoom> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    internal var selectedRoom: ReviewRoom? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Route polyline in map pixels; the last point is the destination. */
    var route: List<PixelPoint> = emptyList()
        set(value) {
            field = value
            if (value.size >= 2) showRoute() else invalidate()
        }

    var pose: PoseEstimate? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Ground-truth positions tapped at checkpoints, drawn as small orange dots. */
    var checkpoints: List<PixelPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Called whenever the visible part of the map may have changed. */
    internal var onViewChange: (() -> Unit)? = null

    /** Whether the view shows less than the whole map. */
    internal val isZoomed: Boolean
        get() = bitmap?.let { toScreen.mapRadius(1f) > wholeMapScale(it) * 1.02f } ?: false

    /** Receives the map-pixel position of a single tap, when set. */
    var onMapTap: ((PixelPoint) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val toScreen = Matrix()
    private val routePath = Path()
    private val screenPath = Path()
    private val point = FloatArray(2)

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val routePaint = stroke(Color.rgb(21, 101, 192), 6f)
    private val haloPaint = stroke(Color.WHITE, 10f)
    private val spreadPaint = fill(Color.argb(50, 13, 71, 161))
    private val dotPaint = fill(Color.rgb(13, 71, 161))
    private val dotRingPaint = stroke(Color.WHITE, 3f)
    private val startPaint = fill(REVIEW_START)
    private val destinationPaint = fill(Color.rgb(198, 40, 40))
    private val reviewLine = stroke(REVIEW_CORRIDOR, 1.8f)
    private val roomLine = stroke(REVIEW_ROOM, 1.2f)
    private val possibleLine = stroke(REVIEW_CHECK, 1.4f).apply {
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
    }
    private val selectedLine = stroke(POSTER_ACCENT, 3f)
    private val selectedRing = stroke(POSTER_ACCENT, 3f)
    private val selectedTag = fill(POSTER_ACCENT)
    private val selectedText = fill(Color.rgb(250, 251, 252)).apply {
        textSize = 14 * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val attentionFill = fill(Color.rgb(253, 236, 214))
    private val clearFill = fill(Color.rgb(238, 247, 240))
    private val insideText = fill(POSTER_TEXT).apply { textAlign = Paint.Align.CENTER }
    private val roomDot = fill(roomLine.color)
    private val roomText = fill(roomLine.color).apply { textSize = 9 * density }
    private val roomTextBackground = fill(Color.rgb(245, 249, 245))
    private val reviewBox = RectF()
    private val checkpointPaint = fill(Color.rgb(239, 108, 0))

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val f = detector.scaleFactor
                toScreen.postScale(f, f, detector.focusX, detector.focusY)
                viewChanged()
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
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                toScreen.postTranslate(-distanceX, -distanceY)
                viewChanged()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val callback = onMapTap ?: return false
                val inverse = Matrix()
                if (!toScreen.invert(inverse)) return false
                val tapped = floatArrayOf(e.x, e.y)
                inverse.mapPoints(tapped)
                callback(PixelPoint(tapped[0].toDouble(), tapped[1].toDouble()))
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                showAll()
                return true
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (route.size >= 2) showRoute() else showAll()
    }

    /** Move to a room, up to the same zoom limit as a route. */
    fun focus(bounds: Rect) {
        val area = RectF(bounds)
        area.inset(-FOCUS_MARGIN_PX, -FOCUS_MARGIN_PX)
        fit(area, margin = 0f)
    }

    /** The room under a tap, with enough slop for small labels but room to tap beside them. */
    internal fun roomNear(tap: PixelPoint): ReviewRoom? {
        val slop = 8 * density / toScreen.mapRadius(1f)
        fun distance(room: ReviewRoom) = room.preview.bounds.let {
            maxOf(
                abs(tap.x - it.exactCenterX()) - it.width() / 2f,
                abs(tap.y - it.exactCenterY()) - it.height() / 2f,
            )
        }
        return reviewRooms.minByOrNull(::distance)?.takeIf { distance(it) <= slop }
    }

    /** Fit the whole map in the view. */
    fun showAll() {
        val image = bitmap ?: return invalidate()
        fit(RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()), margin = 0f)
    }

    /** Zoom to the route with a margin, never further in than 3× the whole-map fit. */
    private fun showRoute() {
        val bounds = RectF(route[0].x.toFloat(), route[0].y.toFloat(), 0f, 0f).apply {
            right = left
            bottom = top
            for (p in route) union(p.x.toFloat(), p.y.toFloat())
        }
        fit(bounds, margin = 48 * density)
    }

    private fun fit(area: RectF, margin: Float) {
        val image = bitmap
        if (image == null || width == 0 || height == 0) return invalidate()
        val wholeMap = wholeMapScale(image)
        val w = (width - 2 * margin).coerceAtLeast(1f)
        val h = (height - 2 * margin).coerceAtLeast(1f)
        val scale = min(min(w / area.width(), h / area.height()), 3 * wholeMap)
        toScreen.setScale(scale, scale)
        val dx = width / 2f - scale * area.centerX()
        val dy = height / 2f - scale * area.centerY()
        toScreen.postTranslate(dx, dy)
        viewChanged()
    }

    private fun wholeMapScale(image: Bitmap) =
        min(width.toFloat() / image.width, height.toFloat() / image.height)

    /** A reviewed map stays on screen, so the photo cannot be lost by zooming or dragging. */
    private fun viewChanged() {
        val image = bitmap
        if (review != null && image != null && width > 0 && height > 0) {
            val whole = wholeMapScale(image)
            val scale = toScreen.mapRadius(1f)
            val allowed = scale.coerceIn(whole, whole * MAX_REVIEW_ZOOM)
            if (allowed != scale) {
                toScreen.postScale(allowed / scale, allowed / scale, width / 2f, height / 2f)
            }
            val corners = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
            toScreen.mapRect(corners)
            toScreen.postTranslate(
                edgeShift(corners.left, corners.right, width.toFloat()),
                edgeShift(corners.top, corners.bottom, height.toFloat()),
            )
        }
        invalidate()
        onViewChange?.invoke()
    }

    private fun edgeShift(start: Float, end: Float, size: Float): Float = when {
        end - start <= size -> (size - end - start) / 2f
        start > 0f -> -start
        end < size -> size - end
        else -> 0f
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        canvas.save()
        canvas.concat(toScreen)
        canvas.drawBitmap(image, 0f, 0f, imagePaint)
        canvas.restore()

        review?.let { drawReview(canvas, it) }

        // Overlays are drawn in screen space so line widths and markers keep their size.
        if (route.size >= 2) {
            routePath.reset()
            routePath.moveTo(route[0].x.toFloat(), route[0].y.toFloat())
            for (p in route.drop(1)) routePath.lineTo(p.x.toFloat(), p.y.toFloat())
            routePath.transform(toScreen, screenPath)
            canvas.drawPath(screenPath, haloPaint)
            canvas.drawPath(screenPath, routePaint)
            marker(canvas, route.last(), destinationPaint)
        }
        // A small dot in review keeps the printed star and nearby corridor visible.
        start?.let { marker(canvas, it, startPaint, if (review == null) 9f else 4.5f) }
        for (truth in checkpoints) {
            val (x, y) = screen(truth)
            canvas.drawCircle(x, y, 5 * density, checkpointPaint)
        }
        pose?.let { drawPose(canvas, it) }
    }

    private fun drawReview(canvas: Canvas, graph: FloorGraph) {
        for (edge in graph.edges) {
            val first = edge.polyline.firstOrNull() ?: continue
            routePath.reset()
            routePath.moveTo(first.x.toFloat(), first.y.toFloat())
            for (p in edge.polyline.drop(1)) routePath.lineTo(p.x.toFloat(), p.y.toFloat())
            routePath.transform(toScreen, screenPath)
            canvas.drawPath(screenPath, reviewLine)
        }

        for (room in reviewRooms) {
            val preview = room.preview
            val door = graph.nodes[preview.entranceNodeId]?.position
            val color = if (room.check == RoomCheck.CLEAR) REVIEW_ROOM else REVIEW_CHECK
            roomLine.color = color
            roomDot.color = color
            roomText.color = color
            reviewBox.set(preview.bounds)
            toScreen.mapRect(reviewBox)
            if (door != null) {
                val (x, y) = screen(door)
                val dx = x - reviewBox.centerX()
                val dy = y - reviewBox.centerY()
                val outside = maxOf(
                    abs(dx) / (reviewBox.width() / 2f).coerceAtLeast(1f),
                    abs(dy) / (reviewBox.height() / 2f).coerceAtLeast(1f),
                )
                if (outside > 1f) {
                    canvas.drawLine(
                        reviewBox.centerX() + dx / outside,
                        reviewBox.centerY() + dy / outside,
                        x,
                        y,
                        roomLine,
                    )
                }
                canvas.drawCircle(x, y, 2.4f * density, roomDot)
            }
            val label = preview.label + if (room.isDoubtful) "?" else ""
            val labelWidth = roomText.measureText(label) + 4f * density
            val fits = labelWidth <= reviewBox.width() && reviewBox.height() >= 12f * density
            // Too small for a tag: the number read replaces the printed one inside the box.
            if (!fits) {
                val back = if (room.check == RoomCheck.CLEAR) clearFill else attentionFill
                canvas.drawRect(reviewBox, back)
                insideText.textSize = 10f
                val widest = insideText.measureText(label).coerceAtLeast(1f)
                insideText.textSize = min(
                    10f * reviewBox.width() * 0.92f / widest,
                    reviewBox.height() * 0.85f,
                )
                canvas.drawText(
                    label,
                    reviewBox.centerX(),
                    reviewBox.centerY() - (insideText.ascent() + insideText.descent()) / 2f,
                    insideText,
                )
            }
            canvas.drawRect(
                reviewBox,
                if (room.check == RoomCheck.POSSIBLE) possibleLine else roomLine,
            )
            // Zoomed in, the tag sits above so the printed number stays visible to compare.
            if (fits && room != selectedRoom) {
                val baseline = reviewBox.top - 2f * density
                canvas.drawRect(
                    reviewBox.left,
                    baseline + roomText.ascent() - density,
                    reviewBox.left + labelWidth,
                    reviewBox.top,
                    roomTextBackground,
                )
                canvas.drawText(label, reviewBox.left + 2f * density, baseline, roomText)
            }
        }
        selectedRoom?.let { drawSelected(canvas, it) }
    }

    /** A ring and a large tag, so the room can be found on the whole map. */
    private fun drawSelected(canvas: Canvas, room: ReviewRoom) {
        reviewBox.set(room.preview.bounds)
        toScreen.mapRect(reviewBox)
        canvas.drawRect(reviewBox, selectedLine)
        val x = reviewBox.centerX()
        val y = reviewBox.centerY()
        val ring = maxOf(reviewBox.width(), reviewBox.height()) / 2f + 8f * density
        canvas.drawCircle(x, y, ring, selectedRing)
        val label = room.preview.label + if (room.isDoubtful) "?" else ""
        val tagWidth = selectedText.measureText(label) + 14f * density
        val tagHeight = 24f * density
        val left = (x - tagWidth / 2f).coerceIn(0f, (width - tagWidth).coerceAtLeast(0f))
        val above = y - ring - tagHeight - 2f * density
        val top = if (above >= 0f) above else y + ring + 2f * density
        reviewBox.set(left, top, left + tagWidth, top + tagHeight)
        canvas.drawRoundRect(reviewBox, 6f * density, 6f * density, selectedTag)
        canvas.drawText(
            label,
            left + 7f * density,
            top + tagHeight / 2f - (selectedText.ascent() + selectedText.descent()) / 2f,
            selectedText,
        )
    }

    private fun screen(p: PixelPoint): FloatArray {
        point[0] = p.x.toFloat()
        point[1] = p.y.toFloat()
        toScreen.mapPoints(point)
        return point
    }

    private fun marker(canvas: Canvas, at: PixelPoint, paint: Paint, radius: Float = 9f) {
        val (x, y) = screen(at)
        canvas.drawCircle(x, y, radius * density, paint)
        canvas.drawCircle(x, y, radius * density, dotRingPaint)
    }

    private fun drawPose(canvas: Canvas, pose: PoseEstimate) {
        val (x, y) = screen(pose.position)
        val spread = pose.spreadPx.toFloat() * toScreen.mapRadius(1f)
        if (spread > 0) canvas.drawCircle(x, y, spread, spreadPaint)
        // Heading wedge: the map is never rotated, so map bearings are screen bearings.
        val h = pose.headingRad
        val tip = 22 * density
        val side = 9 * density
        val wedge = Path().apply {
            moveTo(x + tip * cos(h).toFloat(), y + tip * sin(h).toFloat())
            lineTo(x + side * cos(h + 2.2).toFloat(), y + side * sin(h + 2.2).toFloat())
            lineTo(x + side * cos(h - 2.2).toFloat(), y + side * sin(h - 2.2).toFloat())
            close()
        }
        canvas.drawPath(wedge, dotPaint)
        canvas.drawCircle(x, y, 8 * density, dotPaint)
        canvas.drawCircle(x, y, 8 * density, dotRingPaint)
    }

    private fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private companion object {
        const val FOCUS_MARGIN_PX = 200f
        const val MAX_REVIEW_ZOOM = 8f
    }
}
