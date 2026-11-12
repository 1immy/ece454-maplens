package edu.wisc.maplens.robot

import edu.wisc.maplens.model.PixelPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A place known in both maps: a MapLens point and where temi's map has it (metres). */
data class Anchor(val label: String, val map: PixelPoint, val robotX: Double, val robotY: Double)

/**
 * Converts between temi's map (metres, x/y as its SDK reports, yaw counter-clockwise) and the
 * MapLens map (pixels of the straightened poster, y down, bearings clockwise).
 *
 * A similarity transform (rotation, uniform scale, shift, and the y-flip between the frames)
 * fitted by least squares to two or more [Anchor]s: drive temi to a junction, tap "use temi's
 * position here" for that node, repeat at a second, well-separated junction. More anchors
 * average out placement error; [rmsPx] says how well they agree. A posted map that isn't to
 * scale will show up as a large residual: then anchor near where temi will actually walk.
 */
class MapAlignment private constructor(
    private val a: Double,
    private val b: Double,
    private val tx: Double,
    private val ty: Double,
    /** Root-mean-square misfit of the anchors, map px. */
    val rmsPx: Double,
) {
    /** Map pixels per robot metre. */
    val scale: Double get() = hypot(a, b)

    /** temi metres → MapLens pixels. With q = (x, −y): p = R·s·q + t. */
    fun toMap(x: Double, y: Double): PixelPoint =
        PixelPoint(a * x + b * y + tx, b * x - a * y + ty)

    /** MapLens pixels → temi metres. */
    fun toRobot(p: PixelPoint): Pair<Double, Double> {
        val dx = p.x - tx
        val dy = p.y - ty
        val n = a * a + b * b
        return Pair((a * dx + b * dy) / n, (b * dx - a * dy) / n)
    }

    /** temi yaw (counter-clockwise, radians) → MapLens bearing (clockwise from map +x). */
    fun toMapBearing(
        yaw: Double
    ): Double = atan2(b * cos(yaw) - a * sin(yaw), a * cos(yaw) + b * sin(yaw))

    /** MapLens bearing → temi yaw. */
    fun toRobotYaw(bearing: Double): Double {
        val (x0, y0) = toRobot(PixelPoint(tx, ty))
        val (x1, y1) = toRobot(PixelPoint(tx + cos(bearing), ty + sin(bearing)))
        return atan2(y1 - y0, x1 - x0)
    }

    companion object {
        /** Least-squares fit to [anchors]; null with fewer than two distinct ones. */
        fun fit(anchors: List<Anchor>): MapAlignment? {
            if (anchors.size < 2) return null
            // Work with q = (x, −y) so both frames have the same handedness, then solve
            // p = [a b; b −a]-free form: p.x = a·qx − b'·qy … as a standard similarity on q.
            val qx = anchors.map { it.robotX }
            val qy = anchors.map { -it.robotY }
            val px = anchors.map { it.map.x }
            val py = anchors.map { it.map.y }
            val mqx = qx.average()
            val mqy = qy.average()
            val mpx = px.average()
            val mpy = py.average()
            var sxx = 0.0
            var sxy = 0.0
            var norm = 0.0
            for (i in anchors.indices) {
                val ux = qx[i] - mqx
                val uy = qy[i] - mqy
                val vx = px[i] - mpx
                val vy = py[i] - mpy
                sxx += ux * vx + uy * vy
                sxy += ux * vy - uy * vx
                norm += ux * ux + uy * uy
            }
            if (norm < 1e-9) return null
            // p = [c −d; d c]·q + t, with c = sxx/norm, d = sxy/norm.
            val c = sxx / norm
            val d = sxy / norm
            // In terms of (x, y): p.x = c·x + d·y + tx, p.y = d·x − c·y + ty.
            val tx = mpx - (c * mqx - d * mqy)
            val ty = mpy - (d * mqx + c * mqy)
            val fitted = MapAlignment(c, d, tx, ty, 0.0)
            val rms = sqrt(
                anchors.sumOf {
                    val p = fitted.toMap(it.robotX, it.robotY)
                    val ex = p.x - it.map.x
                    val ey = p.y - it.map.y
                    ex * ex + ey * ey
                } / anchors.size,
            )
            return MapAlignment(c, d, tx, ty, rms)
        }

        /** A made-up alignment for the simulator: [pxPerMeter] px per metre, map origin at 0. */
        fun simulated(
            pxPerMeter: Double
        ): MapAlignment = MapAlignment(pxPerMeter, 0.0, 0.0, 0.0, 0.0)
    }
}
