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
    )

    companion object {
        const val FORMAT_VERSION = 1
    }
}
