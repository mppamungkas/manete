package id.my.pamslab.netlogger.core.model.session

import id.my.pamslab.netlogger.core.model.device.DeviceInfo

enum class Scenario { DRIVE, WALK, STATIC, INDOOR }

enum class EndReason {
    /** Stopped from the notification or the Live screen. */
    USER_STOP,

    /** Found open on the next app or service start: the process was killed. `endedAt` = last measurement. */
    RECOVERED_AFTER_KILL,

    /** The logging loop failed; see logcat. */
    ERROR,
}

/** Event types; from/to formats are listed in docs/columns.md (events.csv). Detected per tick by EventDetector. */
enum class EventType {
    /** networkType changed (LTE, NR_NSA, ...). */
    RAT_CHANGE,

    /** The serving LTE cell changed: another PCI or EARFCN ("pci/earfcn"). */
    PCI_CHANGE_LTE,

    /** The serving NR cell changed while NR stayed up ("pci/arfcn"; only cells with a known PCI). */
    PCI_CHANGE_NR,

    /** A serving NR cell or a current NR signal appeared (the NR leg, not the 5G icon). */
    NR_ADDED,
    NR_REMOVED,

    /** ServiceState left / returned to IN_SERVICE (state numbers). */
    NO_SERVICE,
    SERVICE_RESTORED,

    /** The GPS fix went missing or older than 5 s, after a first fix; back when a fresh fix arrives. */
    GPS_LOST,
    GPS_RESTORED,
    USER_MARKER,

    /** The default data SIM changed during the session; from/to are subscription IDs. The session keeps its SIM. */
    DATA_SIM_CHANGED,
}

/** What is known when a session starts; network fields are filled from the first tick. */
data class SessionStart(
    val uuid: String,
    val startedAtMs: Long,
    val startedElapsedNs: Long,
    val intervalMs: Long,
    val scenario: Scenario?,
    val device: DeviceInfo,
)

data class SessionSummary(
    val id: Long,
    val uuid: String,
    val label: String?,
    val scenario: Scenario?,
    val notes: String?,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val endReason: EndReason?,
    val intervalMs: Long,
    val deviceModel: String,
    val operatorName: String?,
    val measurements: Long,
    val cells: Long,
    val markers: Long,
    val warnings: Long,
)
