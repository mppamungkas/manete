package id.my.pamslab.netlogger.feature.capture.vpn

import android.content.Context
import android.content.Intent

/**
 * Status of a native capture, sent by [NativeCaptureService] (process ":vpn") to the main process as an explicit
 * broadcast limited to NetLogger's own package: [STATS] every second while the capture runs, [ENDED] once at the end
 * (also when it never started). The main process stores events and the sync file; ":vpn" writes no database rows.
 */
object NativeCaptureStatus {
    const val ACTION = "id.my.pamslab.netlogger.capture.NATIVE_STATUS"
    const val EXTRA_EVENT = "event"
    const val EXTRA_PCAP_PATH = "pcap_path"
    const val EXTRA_STATS = "stats"
    const val EXTRA_EXIT = "exit"
    const val EXTRA_WHY = "why"
    const val EXTRA_ERROR = "error"

    const val STATS = "STATS"
    const val ENDED = "ENDED"

    /** Why the service asked the loop to stop. */
    enum class Why { USER, REVOKED, DESTROYED }

    data class Status(
        val event: String,
        val pcapPath: String,
        val stats: NativeCaptureStats?,
        val exit: NativeCaptureExit?,
        val why: Why?,
        val error: String?,
    )

    fun send(context: Context, event: String, pcapPath: String, stats: LongArray?, exit: NativeCaptureExit?, why: Why?, error: String?) {
        context.sendBroadcast(
            Intent(ACTION).setPackage(context.packageName)
                .putExtra(EXTRA_EVENT, event)
                .putExtra(EXTRA_PCAP_PATH, pcapPath)
                .putExtra(EXTRA_STATS, stats)
                .putExtra(EXTRA_EXIT, exit?.name)
                .putExtra(EXTRA_WHY, why?.name)
                .putExtra(EXTRA_ERROR, error),
        )
    }

    fun parse(intent: Intent): Status? {
        if (intent.action != ACTION) return null
        val event = intent.getStringExtra(EXTRA_EVENT) ?: return null
        val path = intent.getStringExtra(EXTRA_PCAP_PATH) ?: return null
        return Status(
            event = event,
            pcapPath = path,
            stats = intent.getLongArrayExtra(EXTRA_STATS)?.let(NativeCaptureStats::of),
            exit = intent.getStringExtra(EXTRA_EXIT)?.let { name -> NativeCaptureExit.entries.firstOrNull { it.name == name } },
            why = intent.getStringExtra(EXTRA_WHY)?.let { name -> Why.entries.firstOrNull { it.name == name } },
            error = intent.getStringExtra(EXTRA_ERROR),
        )
    }
}
