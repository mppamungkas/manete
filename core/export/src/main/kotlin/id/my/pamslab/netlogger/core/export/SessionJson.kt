package id.my.pamslab.netlogger.core.export

import kotlinx.serialization.Serializable

/** session.json: metadata of one exported session. Field definitions: docs/columns.md. */
@Serializable
data class SessionJson(
    val format: String = "netlogger-session",
    val formatVersion: Int = FORMAT_VERSION,
    val uuid: String,
    val label: String?,
    val scenario: String?,
    val notes: String?,
    val startedAt: String?,
    val startedAtMs: Long,
    val startedElapsedRealtimeNs: Long,
    val endedAt: String?,
    val endedAtMs: Long?,
    val endedElapsedRealtimeNs: Long?,
    val endReason: String?,
    val durationMs: Long?,
    val intervalMs: Long,
    val timeZone: String,
    val utcOffset: String,
    val exportedAt: String?,
    val appVersion: String?,
    val schemaVersion: Int,
    val device: Device,
    val network: Network,
    val counts: Counts,
    /** Traffic capture of the session: capture.pcap (in this ZIP), or for old PCAPdroid FILE-mode sessions Download/PCAPdroid/<name>. */
    val captureFile: String? = null,
    val files: List<String>,
) {
    @Serializable
    data class Device(val manufacturer: String, val model: String, val androidVersion: String, val basebandVersion: String?)

    @Serializable
    data class Network(val subscriptionId: Int?, val simSlot: Int?, val operatorName: String?, val mcc: String?, val mnc: String?)

    @Serializable
    data class Counts(
        val measurements: Long,
        val cells: Long,
        val events: Long,
        val warnings: Long,
        val trackPoints: Long,
        val throughputTests: Long = 0,
        /** Floor plan taps, undone ones included (waypoints.csv rows); 0 without a floor plan. */
        val waypoints: Long = 0,
        /** Ticks placed on a floor plan (indoor.csv rows). */
        val indoorTicks: Long = 0,
    )

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** plans.json: one entry per floor plan the session's taps are on (decision 006). Field definitions: docs/columns.md. */
@Serializable
data class PlanJson(
    val uuid: String,
    val name: String,
    val floor: String?,
    val widthPx: Int,
    val heightPx: Int,
    val metresPerPx: Double?,
    /** Active (not undone) taps on this plan. */
    val taps: Int,
    /** plans/<uuid>.png in this ZIP; null when the export left the images out. */
    val image: String?,
    /** Where the plan lies on the map (step 7); null when not placed. */
    val georef: Georef? = null,
) {
    @Serializable
    data class Georef(
        val points: List<GeorefPoint>,
        /** Degrees clockwise from north of the plan's up direction; null if the points give no placement. */
        val rotationDeg: Double?,
        val metresPerPx: Double?,
        /** Average distance between the fitted and placed map points (0 with two points). */
        val rmsM: Double?,
    )

    @Serializable
    data class GeorefPoint(val x: Double, val y: Double, val lat: Double, val lon: Double)
}
