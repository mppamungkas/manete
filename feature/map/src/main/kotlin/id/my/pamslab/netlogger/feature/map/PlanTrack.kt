package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.data.SessionIndoor
import id.my.pamslab.netlogger.core.model.indoor.PlanInterpolator
import id.my.pamslab.netlogger.core.model.indoor.PlanPosition
import id.my.pamslab.netlogger.core.model.indoor.Waypoint
import kotlin.math.hypot

/** What the Map draws: the OSM map with the GPS track, or the session's floor plan (decision 006, step 4). */
enum class MapView { MAP, PLAN }

/** A tick placed on a floor plan (between two taps on it, by time). */
data class PlanPoint(val values: TickValues, val position: PlanPosition)

/**
 * One floor of a session for the plan view: the [plan], the ticks placed on it, the taps on it in time order
 * ([path], undone ones left out), and the other plans the session used ([plans], for the floor menu).
 */
data class PlanTrack(
    val sessionId: Long,
    val plan: FloorPlanSummary,
    val plans: List<FloorPlanSummary>,
    val points: List<PlanPoint>,
    val path: List<Waypoint>,
    /** All ticks of the session, placed or not. */
    val ticks: Int,
)

/**
 * The plan view of [track] on plan [planId], or, when null or not used, the plan with the most taps. Null when the
 * session has no taps. Pure (decision 006, D2: positions are derived from the stored taps).
 */
fun planTrack(track: Track, indoor: SessionIndoor, planId: Long?): PlanTrack? {
    val active = indoor.active
    if (active.isEmpty() || indoor.plans.isEmpty()) return null
    val byTaps = active.groupingBy { it.planId }.eachCount()
    val plan = indoor.plans.firstOrNull { it.plan.id == planId }
        ?: indoor.plans.maxByOrNull { byTaps[it.plan.id] ?: 0 }
        ?: return null
    val positions = PlanInterpolator.positions(track.values.map { it.elapsedRealtimeNs }, active)
    val points = track.values.zip(positions).mapNotNull { (v, p) ->
        p?.takeIf { it.planId == plan.plan.id }?.let { PlanPoint(v, it) }
    }
    return PlanTrack(
        sessionId = track.sessionId,
        plan = plan,
        plans = indoor.plans.sortedByDescending { byTaps[it.plan.id] ?: 0 },
        points = points,
        path = active.filter { it.planId == plan.plan.id },
        ticks = track.values.size,
    )
}

/** The placed tick nearest to plan point [x]/[y], if within [maxDistance] plan pixels. */
fun nearestPoint(points: List<PlanPoint>, x: Float, y: Float, maxDistance: Float): PlanPoint? =
    points.minByOrNull { hypot(it.position.x - x, it.position.y - y) }
        ?.takeIf { hypot(it.position.x - x, it.position.y - y) <= maxDistance }
