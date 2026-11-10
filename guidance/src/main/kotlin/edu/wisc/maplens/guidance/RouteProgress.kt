package edu.wisc.maplens.guidance

import edu.wisc.maplens.model.PixelPoint
import edu.wisc.maplens.model.PoseEstimate
import edu.wisc.maplens.model.bearingDifference

/**
 * When the walker counts as off route, facing the wrong way, or due a new route (#80).
 * Distances are in metres and turned into map pixels with the walker's k and [strideMeters],
 * so they mean the same for every map and walker. Starting values, tuned on EH3 walks.
 */
data class RerouteParams(
    /** Farther than this from the route line is off route... */
    val offRouteMeters: Double = 2.5,
    /** ...once it lasts this many steps in a row, so one stray estimate doesn't reroute. */
    val offRouteSteps: Int = 3,
    /** A heading this far from the route's direction is facing back along it. */
    val backwardDeg: Double = 120.0,
    /** Facing back for this many steps shows "Turn around" (a turn in place is one step). */
    val backwardSteps: Int = 2,
    /**
     * Walking back along the route for this many steps reroutes: going on that way may now be
     * the shorter way round, and if it isn't the new route simply starts with "Turn around".
     */
    val wrongWaySteps: Int = 5,
    /** ...but only if the walker really moved back this far, not just turned on the spot. */
    val wrongWayMeters: Double = 2.0,
    /**
     * Rerouting from a corridor prefers the way the walker already faces: turning back costs
     * this much extra, so a route ahead wins unless turning round saves more than this.
     */
    val uTurnPenaltyMeters: Double = 4.0,
    /** No new reroute for this many steps after one, so two routes can't flip back and forth. */
    val cooldownSteps: Int = 4,
    /** Progress is searched this far behind and ahead of the last known place, in steps. */
    val searchBackSteps: Double = 4.0,
    val searchAheadSteps: Double = 12.0,
    /** Display-only stride used to turn metres into steps. */
    val strideMeters: Double = 0.7,
) {
    /** Map pixels in [meters] for a walker whose step is [k] px. */
    fun px(meters: Double, k: Double): Double = meters / strideMeters * k
}

/** Why a new route is due. */
enum class RerouteReason { OFF_ROUTE, WRONG_WAY }

/** Where the walker is relative to the route after one pose. */
data class RouteStatus(
    /** Progress along the route, px. Held, not guessed, while off route. */
    val progressPx: Double,
    /** Distance from the route line, px. */
    val distancePx: Double,
    /** Off the route line right now (before [RerouteParams.offRouteSteps] confirm it). */
    val offRoute: Boolean,
    /** On the route but facing back along it: show "Turn around". */
    val facingBack: Boolean,
    /** Non-null when a new route should be computed now. */
    val reroute: RerouteReason?,
)

/**
 * Follows one walker along one [RoutePath], one pose at a time, the way a maps app matches a
 * GPS fix to the route:
 *
 * - **Progress** is searched only near where the walker last was (a window of
 *   [RerouteParams.searchBackSteps] behind and [RerouteParams.searchAheadSteps] ahead), so it
 *   can't jump to another stretch of the route that passes close by, even for one stray
 *   estimate. A walker who reaches the route outside that window (a shortcut) is off route
 *   until the reroute starts a new route where they are.
 * - **Off route:** more than [RerouteParams.offRouteMeters] from the line for
 *   [RerouteParams.offRouteSteps] steps in a row asks for a reroute. Progress is held meanwhile.
 * - **Facing back:** heading more than [RerouteParams.backwardDeg] from the route's direction for
 *   [RerouteParams.backwardSteps] steps shows "Turn around" without rerouting yet.
 * - **Wrong way:** still walking back after [RerouteParams.wrongWaySteps] steps and at least
 *   [RerouteParams.wrongWayMeters] asks for a reroute, which may find a shorter way ahead.
 *
 * After a reroute the app makes a new [RouteProgress] for the new route, starting where the
 * walker is, with a cooldown so it can't immediately ask again, and [startFacingBack] when the
 * new route starts behind them. Pure Kotlin; feed it every pose in order.
 */
class RouteProgress(
    val path: RoutePath,
    private val params: RerouteParams = RerouteParams(),
    startPx: Double = 0.0,
    cooldownSteps: Int = 0,
    startFacingBack: Boolean = false,
) {
    /** Last tracked progress along the route, px. */
    var progressPx: Double = startPx.coerceIn(0.0, path.lengthPx)
        private set

    private var offSteps = 0
    private var backSteps = if (startFacingBack) params.backwardSteps else 0

    /** On the route but facing back along it: show "Turn around". */
    val facingBack: Boolean get() = backSteps >= params.backwardSteps
    private var backStartPx = progressPx
    private var cooldown = cooldownSteps

    fun update(pose: PoseEstimate): RouteStatus {
        val k = pose.stepLengthPx
        val near = path.closest(
            pose.position,
            progressPx - params.searchBackSteps * k,
            progressPx + params.searchAheadSteps * k,
        )
        val hit = near
        val off = hit.distancePx > params.px(params.offRouteMeters, k)
        val cooling = cooldown > 0
        if (cooling) cooldown--
        if (off) {
            offSteps++
            backSteps = 0
        } else {
            offSteps = 0
            progressPx = hit.alongPx
        }

        val atEnd = path.lengthPx - progressPx < k
        val backward = !off && !atEnd &&
            bearingDifference(pose.headingRad, path.bearingAt(progressPx)) >
            Math.toRadians(params.backwardDeg)
        if (backward) {
            if (backSteps == 0) backStartPx = progressPx
            backSteps++
        } else {
            backSteps = 0
        }

        val reroute = when {
            cooling -> null
            offSteps >= params.offRouteSteps -> RerouteReason.OFF_ROUTE
            backSteps >= params.wrongWaySteps &&
                backStartPx - progressPx >= params.px(params.wrongWayMeters, k) ->
                RerouteReason.WRONG_WAY
            else -> null
        }
        return RouteStatus(
            progressPx = progressPx,
            distancePx = hit.distancePx,
            offRoute = off,
            facingBack = facingBack,
            reroute = reroute,
        )
    }

    /**
     * Where the walked part of the route should end for a dot drawn at [shown]: the closest
     * point to it within a step of the tracked progress, so the grey "walked" stretch moves
     * with the dot every frame but never runs off to another part of the route.
     */
    fun displayPx(shown: PixelPoint, k: Double): Double =
        path.closest(shown, progressPx - k, progressPx + k).alongPx
}
