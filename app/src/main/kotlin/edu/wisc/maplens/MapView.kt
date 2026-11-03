package edu.wisc.maplens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The straightened map with the route, START, destination and position dot drawn on top.
 * Pinch to zoom, drag to pan, double-tap to show the whole map. Everything is drawn from
 * map-pixel coordinates through one [Matrix], so the overlay always lines up with the image.
 */
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
    private val startPaint = fill(Color.rgb(46, 125, 50))
    private val destinationPaint = fill(Color.rgb(198, 40, 40))

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val f = detector.scaleFactor
                toScreen.postScale(f, f, detector.focusX, detector.focusY)
                invalidate()
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
                invalidate()
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
        val wholeMap = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = (width - 2 * margin).coerceAtLeast(1f)
        val h = (height - 2 * margin).coerceAtLeast(1f)
        val scale = min(min(w / area.width(), h / area.height()), 3 * wholeMap)
        toScreen.setScale(scale, scale)
        val dx = width / 2f - scale * area.centerX()
        val dy = height / 2f - scale * area.centerY()
        toScreen.postTranslate(dx, dy)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        canvas.save()
        canvas.concat(toScreen)
        canvas.drawBitmap(image, 0f, 0f, imagePaint)
        canvas.restore()

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
        start?.let { marker(canvas, it, startPaint) }
        pose?.let { drawPose(canvas, it) }
    }

    private fun screen(p: PixelPoint): FloatArray {
        point[0] = p.x.toFloat()
        point[1] = p.y.toFloat()
        toScreen.mapPoints(point)
        return point
    }

    private fun marker(canvas: Canvas, at: PixelPoint, paint: Paint) {
        val (x, y) = screen(at)
        canvas.drawCircle(x, y, 9 * density, paint)
        canvas.drawCircle(x, y, 9 * density, dotRingPaint)
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
}
