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

enum class EventType {
    RAT_CHANGE, PCI_CHANGE_LTE, PCI_CHANGE_NR, NR_ADDED, NR_REMOVED, NO_SERVICE, SERVICE_RESTORED,
    GPS_LOST, GPS_RESTORED, USER_MARKER,
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
