package id.my.pamslab.netlogger.core.telephony

import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.logging.SessionEvent
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.session.EventType

/**
 * Compares each tick with the previous ones and returns the events it starts. One instance per session, fed every
 * tick in order; pure apart from that state. The first tick only sets the state.
 *
 * Every change at tick resolution is an event, without debouncing, except the serving NR cell: a ping-pong between
 * two LTE cells within a few ticks stays visible (filter in analysis). A new serving NR cell must be seen in
 * [NR_CONFIRM_TICKS] ticks before PCI_CHANGE_NR is written (A56: the serving pick among cells on one NR-ARFCN flips for
 * single ticks); the event keeps the time and position of the first of them. A value missing for a tick (no CellInfo
 * cell, unknown service state) is not a change; the last known value is kept.
 */
class EventDetector {

    private var started = false
    private var networkType: NetworkType? = null
    private var lteCell: String? = null
    private var nrUp = false
    private var nrCell: String? = null
    private var nrCandidate: String? = null
    private var nrCandidateTicks = 0
    private var nrCandidateEvent: SessionEvent? = null
    private var inService: Boolean? = null
    private var serviceState: Int? = null
    private var gpsProblem: String? = null
    private var hadFix = false
    private var dataSubId: Int? = null

    fun detect(tick: Tick): List<SessionEvent> {
        val r = tick.radio
        val events = mutableListOf<SessionEvent>()
        fun event(type: EventType, from: Any?, to: Any?) {
            events += SessionEvent(
                type, r.wallTimeMs, r.elapsedRealtimeNs, from?.toString(), to?.toString(),
                tick.location?.latitude, tick.location?.longitude,
            )
        }
        val first = !started
        started = true

        if (!first && r.networkType != networkType) event(EventType.RAT_CHANGE, networkType, r.networkType)
        networkType = r.networkType

        servingLte(r.cells)?.let { cell ->
            if (!first && lteCell != null && cell != lteCell) event(EventType.PCI_CHANGE_LTE, lteCell, cell)
            lteCell = cell
        }

        val nrNow = r.nsaInputs.nrServingCellInfo || r.nsaInputs.nrSignalStrength
        val nrCellNow = servingNr(r.cells)
        when {
            nrNow && !nrUp -> {
                if (!first) event(EventType.NR_ADDED, null, nrCellNow)
                nrCell = nrCellNow
            }
            !nrNow && nrUp -> {
                event(EventType.NR_REMOVED, nrCell, null)
                nrCell = null
            }
            nrNow && nrCellNow != null -> when {
                nrCell == null -> nrCell = nrCellNow
                nrCellNow == nrCell -> clearNrCandidate()
                nrCellNow == nrCandidate -> nrCandidateTicks++
                else -> {
                    nrCandidate = nrCellNow
                    nrCandidateTicks = 1
                    nrCandidateEvent = SessionEvent(
                        EventType.PCI_CHANGE_NR, r.wallTimeMs, r.elapsedRealtimeNs, nrCell, nrCellNow,
                        tick.location?.latitude, tick.location?.longitude,
                    )
                }
            }
        }
        if (!nrNow) clearNrCandidate()
        if (nrCandidateTicks >= NR_CONFIRM_TICKS) {
            nrCandidateEvent?.let { events += it }
            nrCell = nrCandidate
            clearNrCandidate()
        }
        nrUp = nrNow

        r.serviceState?.let { state ->
            val now = state == STATE_IN_SERVICE
            if (!first && inService == true && !now) event(EventType.NO_SERVICE, serviceState, state)
            if (!first && inService == false && now) event(EventType.SERVICE_RESTORED, serviceState, state)
            inService = now
            serviceState = state
        }

        val problem = gpsProblem(tick)
        if (hadFix) {
            if (gpsProblem == null && problem != null) event(EventType.GPS_LOST, null, problem)
            if (gpsProblem != null && problem == null) event(EventType.GPS_RESTORED, gpsProblem, null)
        }
        if (problem == null) hadFix = true
        gpsProblem = problem

        if (!first && r.dataSubscriptionId != dataSubId) event(EventType.DATA_SIM_CHANGED, dataSubId, r.dataSubscriptionId)
        dataSubId = r.dataSubscriptionId

        return events
    }

    private fun clearNrCandidate() {
        nrCandidate = null
        nrCandidateTicks = 0
        nrCandidateEvent = null
    }

    private fun gpsProblem(tick: Tick): String? = when {
        tick.locationStatus != LocationStatus.OK -> tick.locationStatus.name
        tick.location == null -> NO_FIX
        (tick.locationAgeMs ?: Long.MAX_VALUE) > GPS_MAX_AGE_MS -> STALE_FIX
        else -> null
    }

    companion object {
        /** ServiceState.STATE_IN_SERVICE. */
        private const val STATE_IN_SERVICE = 0

        /** A fix older than this counts as lost (fixes arrive at ~1 Hz; the Live screen grays values after 5 s). */
        const val GPS_MAX_AGE_MS = 5_000L
        const val NO_FIX = "NO_FIX"
        const val STALE_FIX = "STALE_FIX"

        /** Ticks a new serving NR cell must be seen in before PCI_CHANGE_NR. */
        const val NR_CONFIRM_TICKS = 2

        /** "pci/earfcn" of the serving LTE cell, chosen as in flat.csv: registered, else PRIMARY_SERVING, else first. */
        fun servingLte(cells: List<CellSample>): String? {
            val serving = cells.filter { it.rat == Rat.LTE && it.source == CellSource.CELL_INFO && it.servingInferred }
            val cell = serving.firstOrNull { it.isRegistered == true }
                ?: serving.firstOrNull { it.connectionStatus == ConnectionStatus.PRIMARY_SERVING }
                ?: serving.firstOrNull()
            return cell?.key()
        }

        /** "pci/arfcn" of the serving NR cell with the strongest SS-RSRP, as in flat.csv; null without a PCI. */
        fun servingNr(cells: List<CellSample>): String? =
            cells.filter { it.rat == Rat.NR && it.source == CellSource.CELL_INFO && it.servingInferred }
                .maxByOrNull { it.rsrpDbm ?: Int.MIN_VALUE }
                ?.key()

        private fun CellSample.key(): String? = pci?.let { "$it/${arfcn ?: ""}" }
    }
}
