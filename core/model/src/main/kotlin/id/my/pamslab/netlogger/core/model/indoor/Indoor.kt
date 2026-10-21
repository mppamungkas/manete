package id.my.pamslab.netlogger.core.model.indoor

/**
 * A floor plan image the user imported (decision 006). Positions on it are pixels of the stored image (top-left
 * origin, x to the right, y down); [metresPerPx] is set when the user calibrated a scale.
 */
data class FloorPlan(
    val id: Long,
    val uuid: String,
    val name: String,
    /** Floor label as the user wrote it, e.g. "2nd floor"; null if not given. */
    val floor: String?,
    val widthPx: Int,
    val heightPx: Int,
    val metresPerPx: Double? = null,
)

/** What a tap meant: [HERE] "I am here now", [STILL] "I am still here" (stood at the last spot meanwhile). */
enum class WaypointKind { HERE, STILL }

/**
 * One tap on a floor plan during a session: the raw input from which tick positions are derived ([PlanInterpolator]).
 * [removed]: undone by the user; kept for the export, never used for positions.
 */
data class Waypoint(
    val planId: Long,
    val wallTimeMs: Long,
    val elapsedRealtimeNs: Long,
    val x: Double,
    val y: Double,
    val kind: WaypointKind = WaypointKind.HERE,
    val removed: Boolean = false,
)

/**
 * A tick's derived position on [planId], in pixels of the stored image. [waypointGapS]: seconds to the nearest
 * waypoint, a measure of how far the tick is from what the user actually tapped.
 */
data class PlanPosition(
    val planId: Long,
    val x: Double,
    val y: Double,
    val waypointGapS: Double,
) {
    /** Metres from the plan's top-left corner, or null without a scale. */
    fun metres(plan: FloorPlan): Pair<Double, Double>? = plan.metresPerPx?.let { x * it to y * it }
}

/**
 * Places ticks on the floor plan (decision 006, D1/D2): between two consecutive waypoints on the same plan, on the
 * straight line between them in proportion to time (constant walking speed between taps). No position, never a guess,
 * for ticks before the first waypoint, after the last one, or between waypoints on different plans (a floor change).
 * Removed (undone) waypoints are ignored. Pure: the stored waypoints are the raw truth, positions are derived when
 * shown or exported, so the method can improve for old sessions too.
 */
object PlanInterpolator {

    private const val NS_PER_S = 1e9

    /** Position at [elapsedRealtimeNs] (the tick's time), or null. */
    fun at(elapsedRealtimeNs: Long, waypoints: List<Waypoint>): PlanPosition? = Track(waypoints).at(elapsedRealtimeNs)

    /** Positions for many ticks (any order), aligned with [ticksElapsedNs]. */
    fun positions(ticksElapsedNs: List<Long>, waypoints: List<Waypoint>): List<PlanPosition?> {
        val track = Track(waypoints)
        return ticksElapsedNs.map(track::at)
    }

    /** The active waypoints in time order; equal times keep their stored order (the later tap wins). */
    private class Track(waypoints: List<Waypoint>) {
        private val points = waypoints.filterNot { it.removed }.sortedBy { it.elapsedRealtimeNs }
        private val times = LongArray(points.size) { points[it].elapsedRealtimeNs }

        fun at(t: Long): PlanPosition? {
            if (points.isEmpty() || t < times.first() || t > times.last()) return null
            // Index of the last waypoint at or before t.
            var i = upperBound(t) - 1
            val a = points[i]
            if (t == a.elapsedRealtimeNs) return PlanPosition(a.planId, a.x, a.y, 0.0)
            val b = points[i + 1] // exists: t < times.last()
            if (a.planId != b.planId) return null
            val span = (b.elapsedRealtimeNs - a.elapsedRealtimeNs).toDouble()
            val f = (t - a.elapsedRealtimeNs) / span
            val gapNs = minOf(t - a.elapsedRealtimeNs, b.elapsedRealtimeNs - t)
            return PlanPosition(a.planId, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, gapNs / NS_PER_S)
        }

        /** First index whose time is greater than [t]. */
        private fun upperBound(t: Long): Int {
            var lo = 0
            var hi = times.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= t) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

/** Sizes and the scale calibration of floor plans (decision 006, step 2). Pure, unit-tested. */
object PlanGeometry {
    /** Longest side of a stored plan image: enough for room-level detail, ~45 MB as a bitmap at most. */
    const val MAX_SIDE_PX = 4096

    /** PDF pages are rendered at this resolution (300 dpi), within [MAX_SIDE_PX]. */
    private const val PDF_DPI = 300.0
    private const val PDF_POINTS_PER_INCH = 72.0

    /** Two calibration points closer than this (in stored pixels) give no usable scale. */
    const val MIN_CALIBRATION_PX = 10.0

    /** The size an image of [width] × [height] is stored at: fits [maxSide], never enlarged, at least 1 px. */
    fun storedSize(width: Int, height: Int, maxSide: Int = MAX_SIDE_PX): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val f = minOf(1.0, maxSide.toDouble() / maxOf(width, height))
        return maxOf(1, Math.round(width * f).toInt()) to maxOf(1, Math.round(height * f).toInt())
    }

    /** Render size of a PDF page of [widthPt] × [heightPt] points (1/72 inch): 300 dpi, within [MAX_SIDE_PX]. */
    fun pdfRenderSize(widthPt: Int, heightPt: Int): Pair<Int, Int> {
        val f = PDF_DPI / PDF_POINTS_PER_INCH
        return storedSize(Math.round(widthPt * f).toInt(), Math.round(heightPt * f).toInt())
    }

    /**
     * Metres per stored pixel from two points the user tapped and the real distance between them; null when the points
     * are too close ([MIN_CALIBRATION_PX]) or the distance is not positive.
     */
    fun metresPerPx(x1: Double, y1: Double, x2: Double, y2: Double, metres: Double): Double? {
        val px = Math.hypot(x2 - x1, y2 - y1)
        if (px < MIN_CALIBRATION_PX || !(metres > 0) || metres.isInfinite()) return null
        return metres / px
    }
}
