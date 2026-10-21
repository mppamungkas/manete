package id.my.pamslab.netlogger.feature.logger

import id.my.pamslab.netlogger.core.model.logging.SessionEvent
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.session.EventType
import id.my.pamslab.netlogger.core.telephony.EventDetector

/** An event as Live shows it (decision 004): the stored event plus the serving level just before and at detection. */
data class LiveEvent(
    val type: EventType,
    val wallTimeMs: Long,
    val fromValue: String?,
    val toValue: String?,
    /** RSRP of the changed RAT's serving cell (LTE or NR) on the tick before the event; null for other events. */
    val levelBeforeDbm: Int? = null,
    /** The same on the tick the event was detected. */
    val levelAfterDbm: Int? = null,
)

/**
 * Keeps the last [max] events of a run of ticks, newest first, with the serving RSRP around cell changes. Fed the
 * ticks and the [EventDetector]'s events in order; an NR cell change is confirmed one tick late, so the "before" level
 * is taken from the newest kept tick older than the event, not simply the previous tick.
 */
class LiveEventTracker(private val max: Int = MAX_EVENTS) {
    private val history = ArrayDeque<Tick>()
    var events: List<LiveEvent> = emptyList()
        private set

    fun add(tick: Tick, detected: List<SessionEvent>): List<LiveEvent> {
        if (detected.isNotEmpty()) {
            val new = detected.map { e ->
                val rat = when (e.type) {
                    EventType.PCI_CHANGE_LTE -> Rat.LTE
                    EventType.PCI_CHANGE_NR -> Rat.NR
                    else -> null
                }
                val before = history.lastOrNull { it.radio.elapsedRealtimeNs < e.elapsedRealtimeNs }
                LiveEvent(
                    e.type, e.wallTimeMs, e.fromValue, e.toValue,
                    levelBeforeDbm = rat?.let { r -> before?.let { level(it, r) } },
                    levelAfterDbm = rat?.let { level(tick, it) },
                )
            }
            events = (new.reversed() + events).take(max)
        }
        history.addLast(tick)
        while (history.size > HISTORY_TICKS) history.removeFirst()
        return events
    }

    private fun level(tick: Tick, rat: Rat): Int? = servingColumns(tick.radio.cells).firstOrNull { it.rat == rat }?.level?.value

    private companion object {
        const val MAX_EVENTS = 5

        /** Enough for the NR confirmation delay (EventDetector.NR_CONFIRM_TICKS). */
        const val HISTORY_TICKS = 4
    }
}

/** The event type in words for Live; the export keeps the type names. */
internal fun EventType.title(): String = when (this) {
    EventType.RAT_CHANGE -> "Network type change"
    EventType.PCI_CHANGE_LTE -> "LTE cell change"
    EventType.PCI_CHANGE_NR -> "5G cell change"
    EventType.NR_ADDED -> "5G added"
    EventType.NR_REMOVED -> "5G removed"
    EventType.NO_SERVICE -> "No service"
    EventType.SERVICE_RESTORED -> "Service back"
    EventType.GPS_LOST -> "GPS lost"
    EventType.GPS_RESTORED -> "GPS back"
    EventType.USER_MARKER -> "Marker"
    EventType.DATA_SIM_CHANGED -> "Data SIM changed"
    EventType.CAPTURE_STARTED -> "Capture started"
    EventType.CAPTURE_STOPPED -> "Capture stopped"
    EventType.FLOOR_CHANGE -> "Floor plan"
}

/** The from → to part in words, e.g. "PCI 123 → 211", "LTE → 5G NSA", "-104 → -93 dBm" appended for cell changes. */
internal fun LiveEvent.detail(): String {
    val change = when (type) {
        EventType.RAT_CHANGE -> "${networkName(fromValue)} → ${networkName(toValue)}"
        EventType.PCI_CHANGE_LTE, EventType.PCI_CHANGE_NR -> cellChange(fromValue, toValue)
        EventType.NR_ADDED -> toValue?.let { "PCI ${it.substringBefore('/')}" }
        EventType.GPS_LOST -> gpsReason(toValue)
        EventType.DATA_SIM_CHANGED -> "subId ${fromValue ?: "–"} → ${toValue ?: "–"}"
        EventType.FLOOR_CHANGE -> listOfNotNull(fromValue, toValue).joinToString(" → ")
        else -> null
    }
    val level = if (levelBeforeDbm != null || levelAfterDbm != null) "${levelBeforeDbm ?: "–"} → ${levelAfterDbm ?: "–"} dBm" else null
    return listOfNotNull(change, level).joinToString("   ")
}

/** "pci/arfcn" values: "PCI 123 → 211" on the same channel, else "123/1625 → 211/1850". */
private fun cellChange(from: String?, to: String?): String {
    val (fp, fa) = (from ?: "–/").split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val (tp, ta) = (to ?: "–/").split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    return if (fa == ta) "PCI $fp → $tp" else "${from ?: "–"} → ${to ?: "–"}"
}

private fun networkName(value: String?): String =
    NetworkType.entries.firstOrNull { it.name == value }?.let { networkLabel(it, emptyList()) } ?: value ?: "–"

private fun gpsReason(value: String?): String? = when (value) {
    EventDetector.NO_FIX -> "no fix"
    EventDetector.STALE_FIX -> "fix older than 5 s"
    "DISABLED" -> "location off"
    "NO_PERMISSION" -> "no permission"
    null -> null
    else -> value.lowercase()
}
