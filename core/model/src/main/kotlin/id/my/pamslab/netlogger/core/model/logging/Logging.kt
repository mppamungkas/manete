package id.my.pamslab.netlogger.core.model.logging

import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot

/**
 * One logging tick: the radio snapshot plus the latest GPS fix and device context at that moment. Timestamps
 * (`wallTimeMs`, `elapsedRealtimeNs`) are the radio snapshot's.
 */
data class Tick(
    /** 0-based, consecutive within a session. */
    val seq: Long,
    val radio: RadioSnapshot,
    val location: LocationFix?,
    val locationStatus: LocationStatus,
    /** Tick time minus the fix time; null without a fix. */
    val locationAgeMs: Long?,
    val device: DeviceContext,
)

/** "Add marker" from the notification or the Live screen; becomes a USER_MARKER event in milestone 4. */
data class UserMarker(
    val wallTimeMs: Long,
    val elapsedRealtimeNs: Long,
    val latitude: Double?,
    val longitude: Double?,
)

/** Tick accounting for the "≥ 95 % of expected ticks" requirement. */
data class TickStats(
    val startedElapsedNs: Long,
    val intervalMs: Long,
    val recorded: Long,
    /** Ticks the interval would have produced from start to the latest tick (inclusive). */
    val expected: Long,
) {
    val missed: Long get() = (expected - recorded).coerceAtLeast(0)
    val recordedPct: Double get() = if (expected == 0L) 100.0 else recorded * 100.0 / expected
}
