package id.my.pamslab.netlogger.permissions

import android.Manifest
import android.annotation.SuppressLint

/** Runtime permission groups shown on the rationale screen. Pure logic, unit-tested on the JVM. */
enum class PermissionGroup(val title: String, val rationale: String, val requiredForLogging: Boolean) {
    LOCATION(
        title = "Precise location",
        rationale = "Each measurement is tagged with a GPS fix, and Android only returns cell " +
            "information (PCI, cell ID, neighbors) to apps with precise location.",
        requiredForLogging = true,
    ),
    PHONE(
        title = "Phone state",
        rationale = "Needed to read the network type, the 5G (NSA) display state, service state " +
            "and the active SIM. Manete never reads call or SMS content.",
        requiredForLogging = true,
    ),
    NOTIFICATIONS(
        title = "Notifications",
        rationale = "Shows the ongoing logging notification with Stop and Add Marker actions. " +
            "Logging still works without it, but the notification will be hidden.",
        requiredForLogging = false,
    ),
    ;

    /** Manifest permissions for this group on the given API level; empty if not applicable. */
    @SuppressLint("InlinedApi") // POST_NOTIFICATIONS is gated on sdkInt, which lint cannot follow.
    fun manifestPermissions(sdkInt: Int): List<String> = when (this) {
        LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        PHONE -> listOf(Manifest.permission.READ_PHONE_STATE)
        NOTIFICATIONS ->
            if (sdkInt >= API_TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    }

    companion object {
        /** Build.VERSION_CODES.TIRAMISU, inlined so this file needs no Android runtime. */
        const val API_TIRAMISU = 33
    }
}

enum class PermissionStatus {
    GRANTED,

    /** Location only: the user chose "Approximate". Cell info and GPS need precise location. */
    APPROXIMATE_ONLY,
    DENIED,

    /** The system will no longer show the dialog; the user must use app settings. */
    PERMANENTLY_DENIED,

    /** The permission does not exist on this API level (e.g. notifications below Android 13). */
    NOT_APPLICABLE,
}

data class GroupState(val group: PermissionGroup, val status: PermissionStatus)

data class PermissionUiState(val groups: List<GroupState>) {
    /** True when everything required for a logging session is granted. */
    val canLog: Boolean
        get() = groups.filter { it.group.requiredForLogging }.all { it.status == PermissionStatus.GRANTED }

    val allResolved: Boolean
        get() = groups.all { it.status == PermissionStatus.GRANTED || it.status == PermissionStatus.NOT_APPLICABLE }

    val anyPermanentlyDenied: Boolean
        get() = groups.any { it.status == PermissionStatus.PERMANENTLY_DENIED }
}

fun evaluatePermissions(
    sdkInt: Int,
    isGranted: (String) -> Boolean,
    permanentlyDenied: Set<String>,
): PermissionUiState = PermissionUiState(
    PermissionGroup.entries.map { group ->
        val permissions = group.manifestPermissions(sdkInt)
        val status = when {
            permissions.isEmpty() -> PermissionStatus.NOT_APPLICABLE
            permissions.all(isGranted) -> PermissionStatus.GRANTED
            group == PermissionGroup.LOCATION && isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ->
                // Re-requesting FINE may upgrade approximate to precise, unless the system has stopped asking.
                if (Manifest.permission.ACCESS_FINE_LOCATION in permanentlyDenied) {
                    PermissionStatus.PERMANENTLY_DENIED
                } else {
                    PermissionStatus.APPROXIMATE_ONLY
                }
            permissions.any { it in permanentlyDenied && !isGranted(it) } -> PermissionStatus.PERMANENTLY_DENIED
            else -> PermissionStatus.DENIED
        }
        GroupState(group, status)
    },
)

/** Permissions worth passing to the system dialog: not granted and not permanently denied. */
fun permissionsToRequest(
    sdkInt: Int,
    isGranted: (String) -> Boolean,
    permanentlyDenied: Set<String>,
): List<String> = PermissionGroup.entries
    .flatMap { it.manifestPermissions(sdkInt) }
    .filter { !isGranted(it) && it !in permanentlyDenied }
