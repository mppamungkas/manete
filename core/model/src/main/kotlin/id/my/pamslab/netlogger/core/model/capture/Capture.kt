package id.my.pamslab.netlogger.core.model.capture

/**
 * How the traffic is captured: through PCAPdroid (CLAUDE.md Phase 3a) or NetLogger's own VpnService (Phase 3b).
 * - [TCP]: PCAPdroid's TCP exporter streams the PCAP to NetLogger on 127.0.0.1 and NetLogger writes capture.pcap.
 *   Preferred: NetLogger owns the file, and TCP does not drop packets (the UDP exporter lost ~46 % of a 10 MB
 *   download on the moto g45, 2026-10-14, and was removed).
 * - [FILE]: PCAPdroid writes Download/PCAPdroid/<name>.pcap itself; NetLogger only links the name to the session.
 * - [NATIVE]: NetLogger's own VpnService + libnetcapture (milestone 11, docs/decisions/002-native-capture.md) write
 *   capture.pcap and connections.csv; packet timestamps are elapsedRealtimeNs plus one fixed offset.
 */
enum class CaptureMode { TCP, FILE, NATIVE }

/** Traffic capture of the open session, shared by the capture card, the speed test and the logging engine. */
data class CaptureState(
    val running: Boolean = false,
    val sessionId: Long? = null,
    val mode: CaptureMode? = null,
    /** TCP: the file NetLogger writes; FILE: PCAPdroid's file name in Download/PCAPdroid. */
    val fileName: String? = null,
    val startedElapsedNs: Long? = null,
    /** Packets NetLogger received and wrote (TCP mode) or wrote to the pcap (NATIVE mode). */
    val packets: Long = 0,
    val bytes: Long = 0,
    /** NATIVE: packets that crossed the VPN (the pcap should hold all of them) and pcap records lost to write errors. */
    val expectedPackets: Long? = null,
    val droppedPackets: Long? = null,
    /** NATIVE: connections forwarded so far. */
    val connections: Long? = null,
    /** Packets PCAPdroid counted (pkts_sent + pkts_rcvd), known after the capture stopped. */
    val pcapdroidPackets: Long? = null,
    /** The last capture's problem (PCAPdroid missing, consent denied, stream error), for the UI. */
    val error: String? = null,
)
