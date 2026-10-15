package id.my.pamslab.netlogger.core.model.capture

/** Traffic capture of the open session, shared by the capture card, the speed test and the logging engine. */
data class CaptureState(
    val running: Boolean = false,
    val sessionId: Long? = null,
    /** The pcap NetLogger writes: files/captures/<session uuid>.pcap. */
    val fileName: String? = null,
    val startedElapsedNs: Long? = null,
    /** Packets written to the pcap and their bytes. */
    val packets: Long = 0,
    val bytes: Long = 0,
    /** Packets that crossed the VPN (the pcap should hold all of them) and pcap records lost to write errors. */
    val expectedPackets: Long? = null,
    val droppedPackets: Long? = null,
    /** Connections forwarded so far. */
    val connections: Long? = null,
    /** The last capture's problem (consent denied, another VPN, capture process died), for the UI. */
    val error: String? = null,
)
