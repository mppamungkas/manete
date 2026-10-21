package id.my.pamslab.netlogger.feature.logger.service

import id.my.pamslab.netlogger.core.model.indoor.FloorPlan
import id.my.pamslab.netlogger.core.model.indoor.Waypoint
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.feature.logger.servingColumns

/** A tap of this session with its database id (for Undo). */
data class StoredWaypoint(val id: Long, val waypoint: Waypoint)

/**
 * What the Live plan view colours a tick by: the serving values as Live shows them (CellInfo, gaps filled from
 * SignalStrength, decision 004). Kept in memory for the running session only; the export reads the database.
 */
data class PlanTick(
    val elapsedRealtimeNs: Long,
    val networkType: String,
    val lteRsrp: Int?,
    val nrRsrp: Int?,
    val lteSinr: Int?,
    val nrSinr: Int?,
) {
    companion object {
        fun of(tick: Tick): PlanTick {
            val columns = servingColumns(tick.radio.cells)
            val lte = columns.firstOrNull { it.rat == Rat.LTE }
            val nr = columns.firstOrNull { it.rat == Rat.NR }
            return PlanTick(
                tick.radio.elapsedRealtimeNs, tick.radio.networkType.name,
                lte?.level?.value, nr?.level?.value, lte?.sinr?.value, nr?.sinr?.value,
            )
        }
    }
}

/**
 * The running session's indoor positioning (decision 006): the plan the user taps on, every tap (undone ones too,
 * as stored) and the ticks to place along them.
 */
data class IndoorState(
    val plan: FloorPlan? = null,
    val waypoints: List<StoredWaypoint> = emptyList(),
    val ticks: List<PlanTick> = emptyList(),
) {
    val active: List<Waypoint> get() = waypoints.map { it.waypoint }.filterNot { it.removed }

    /** The newest tap that was not undone, on any plan. */
    val lastActive: StoredWaypoint? get() = waypoints.lastOrNull { !it.waypoint.removed }

    fun withTick(tick: PlanTick) = copy(ticks = (ticks + tick).takeLast(MAX_TICKS))

    companion object {
        /** 6 h at one tick per second; older ticks drop out of the Live view (the database keeps them). */
        const val MAX_TICKS = 21_600

        /** "Gedung C lantai 2 · Lantai 2": how a plan appears in FLOOR_CHANGE events. */
        fun label(plan: FloorPlan) = listOfNotNull(plan.name, plan.floor).joinToString(" · ")
    }
}
