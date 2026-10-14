package id.my.pamslab.netlogger.core.model.capture

/**
 * How PCAPdroid hands over the packets (CLAUDE.md Phase 3a).
 * - [TCP]: PCAPdroid's TCP exporter streams the PCAP to NetLogger on 127.0.0.1 and NetLogger writes capture.pcap.
 *   Preferred: NetLogger owns the file, and TCP does not drop packets (the UDP exporter lost ~46 % of a 10 MB
 *   download on the moto g45, 2026-10-14, and was removed).
 * - [FILE]: PCAPdroid writes Download/PCAPdroid/<name>.pcap itself; NetLogger only links the name to the session.
 */
enum class CaptureMode { TCP, FILE }

/** Traffic capture of the open session, shared by the capture card, the speed test and the logging engine. */
data class CaptureState(
    val running: Boolean = false,
    val sessionId: Long? = null,
    val mode: CaptureMode? = null,
    /** TCP: the file NetLogger writes; FILE: PCAPdroid's file name in Download/PCAPdroid. */
    val fileName: String? = null,
    val startedElapsedNs: Long? = null,
    /** Packets NetLogger received and wrote (TCP mode). */
    val packets: Long = 0,
    val bytes: Long = 0,
    /** Packets PCAPdroid counted (pkts_sent + pkts_rcvd), known after the capture stopped. */
    val pcapdroidPackets: Long? = null,
    /** The last capture's problem (PCAPdroid missing, consent denied, stream error), for the UI. */
    val error: String? = null,
)
