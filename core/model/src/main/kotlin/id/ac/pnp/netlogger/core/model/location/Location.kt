package id.ac.pnp.netlogger.core.model.location

/** One position fix. Optional fields are null when the provider did not report them. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val accuracyM: Float?,
    /** Location.getElapsedRealtimeNanos(): when the fix was taken; the base for location age. */
    val elapsedRealtimeNs: Long,
    /** Location.getTime(). */
    val wallTimeMs: Long,
    val provider: String?,
)

enum class LocationStatus {
    /** Requested, no fix yet. */
    WAITING,
    OK,
    NO_PERMISSION,

    /** Location services are switched off in system settings. */
    DISABLED,

    /** Google Play services missing or too old; FusedLocationProviderClient cannot run. */
    UNAVAILABLE,

    /** The request failed for another reason; see [LocationState.error]. */
    ERROR,
}

/** Latest location state. [fix] keeps the last fix even when the status changes (its age then grows). */
data class LocationState(
    val status: LocationStatus,
    val fix: LocationFix? = null,
    val error: String? = null,
)
