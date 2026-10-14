package id.my.pamslab.netlogger.core.model.capture

/**
 * How PCAPdroid hands over the packets (CLAUDE.md Phase 3a).
 * - [UDP]: PCAPdroid's UDP exporter sends them to NetLogger on 127.0.0.1, NetLogger writes capture.pcap and notes
 *   when each second's first packet arrived (elapsedRealtimeNs). Preferred: NetLogger owns the file and the timing.
 * - [FILE]: PCAPdroid writes Download/PCAPdroid/<name>.pcap itself; NetLogger only links the name to the session.
 */
enum class CaptureMode { UDP, FILE }

/** Traffic capture of the open session, shared by the capture card, the speed test and the logging engine. */
data class CaptureState(
    val running: Boolean = false,
    val sessionId: Long? = null,
    val mode: CaptureMode? = null,
    /** UDP: the file NetLogger writes; FILE: PCAPdroid's file name in Download/PCAPdroid. */
    val fileName: String? = null,
    val startedElapsedNs: Long? = null,
    val packets: Long = 0,
    val bytes: Long = 0,
    /** UDP datagrams that were not a valid PCAP record (or arrived before the PCAP header); not written. */
    val rejectedDatagrams: Long = 0,
    /** The last capture's problem (PCAPdroid missing, consent denied, socket error), for the UI. */
    val error: String? = null,
)
