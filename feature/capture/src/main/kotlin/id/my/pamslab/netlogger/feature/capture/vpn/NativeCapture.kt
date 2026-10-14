package id.my.pamslab.netlogger.feature.capture.vpn

import android.util.Log

/**
 * libnetcapture, the native part of the VpnService capture (Phase 3b, docs/decisions/002-native-capture.md).
 * Loaded on first use. If it can't be loaded (an ABI we don't build), only native capture is unavailable.
 *
 * A capture is a native handle: [create], then [run] on a dedicated thread (blocks until the capture ends), [stop] and
 * [stats] from any thread, [destroy] once [run] returned.
 */
object NativeCapture {

    /** Why the library could not be loaded, or null when it is loaded. */
    val loadError: String? by lazy {
        try {
            System.loadLibrary("netcapture")
            null
        } catch (e: UnsatisfiedLinkError) {
            Log.w("NetLogger", "libnetcapture not loaded", e)
            e.message ?: "libnetcapture not loaded"
        }
    }

    val available: Boolean get() = loadError == null

    /** Library version, ABI and compiler, or null when the library is not loaded. */
    fun version(): String? = if (available) nativeVersion() else null

    /** A new capture handle, or 0 when out of memory. */
    internal fun create(): Long = nativeCreate()

    /**
     * Runs the capture loop on the calling thread until [stop] or until the tun fails. Takes over [pcapFd] (closed
     * at the end); [tunFd] stays open (the caller closes the tun). [callbacks] are called on this thread.
     */
    internal fun run(handle: Long, callbacks: NativeCaptureCallbacks, tunFd: Int, pcapFd: Int, config: Config): NativeCaptureExit =
        NativeCaptureExit.of(
            nativeRun(
                handle, callbacks, tunFd, pcapFd, config.mtu, config.snaplen, config.pcapBufferBytes,
                config.pcapFlushIntervalMs, config.ipv6,
            ),
        )

    internal fun stop(handle: Long) = nativeStop(handle)

    internal fun stats(handle: Long): NativeCaptureStats? = statsArray(handle)?.let(NativeCaptureStats::of)

    /** The raw counters, as sent to the main process (NativeCaptureStatus). */
    internal fun statsArray(handle: Long): LongArray? = nativeStats(handle)

    internal fun destroy(handle: Long) = nativeDestroy(handle)

    data class Config(
        val mtu: Int,
        val ipv6: Boolean,
        val snaplen: Int = 65_535,
        val pcapBufferBytes: Int = 1 shl 20,
        val pcapFlushIntervalMs: Long = 1_000,
    )

    @JvmStatic private external fun nativeVersion(): String
    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeRun(
        handle: Long, callbacks: NativeCaptureCallbacks, tunFd: Int, pcapFd: Int, mtu: Int, snaplen: Int, bufferBytes: Int,
        flushIntervalMs: Long, ipv6: Boolean,
    ): Int
    @JvmStatic private external fun nativeStop(handle: Long)
    @JvmStatic private external fun nativeStats(handle: Long): LongArray?
    @JvmStatic private external fun nativeDestroy(handle: Long)
}

/** Why a native capture ended (CAPTURE_EXIT_* in capture_vpn.h). */
enum class NativeCaptureExit {
    STOPPED, TUN_CLOSED, SELECT_ERROR, INIT_ERROR, PCAP_ERROR, UNKNOWN;

    companion object {
        fun of(code: Int) = entries.getOrNull(code)?.takeIf { it != UNKNOWN } ?: UNKNOWN
    }
}

/** Counters of a native capture (capture_vpn_stats_t; array order fixed in netcapture_jni.c). */
data class NativeCaptureStats(
    val running: Boolean,
    val exit: NativeCaptureExit,
    val tunRxPackets: Long,
    val tunRxBytes: Long,
    val tunTxPackets: Long,
    val tunTxBytes: Long,
    val tunTxErrors: Long,
    val unparsed: Long,
    val fragments: Long,
    val ipverDisabled: Long,
    val tcpWithoutConn: Long,
    val connsFailed: Long,
    val protectFailed: Long,
    val connsTcp: Long,
    val connsUdp: Long,
    val connsIcmp: Long,
    val connsActive: Long,
    val pcapPackets: Long,
    val pcapBytes: Long,
    val pcapTruncated: Long,
    val pcapDropped: Long,
    val pcapLastErrno: Long,
    /** Packet timestamp (ns since epoch) minus this = elapsedRealtimeNs. */
    val wallMinusElapsedNs: Long,
    val clockUncertaintyNs: Long,
    val startElapsedNs: Long,
    val stopElapsedNs: Long,
    /** TCP/UDP connections whose owning app could not be resolved. */
    val connsUidUnknown: Long,
) {
    /** Packets the pcap should hold: every packet read from the tun and every packet written to it. */
    val expectedPcapPackets: Long get() = tunRxPackets + tunTxPackets

    fun summary() = "rx $tunRxPackets pkts/$tunRxBytes B, tx $tunTxPackets pkts/$tunTxBytes B, " +
        "pcap $pcapPackets (dropped $pcapDropped, expected $expectedPcapPackets), " +
        "conns tcp $connsTcp udp $connsUdp icmp $connsIcmp active $connsActive failed $connsFailed, " +
        "tunTxErrors $tunTxErrors unparsed $unparsed fragments $fragments ipv6Off $ipverDisabled " +
        "tcpWithoutConn $tcpWithoutConn protectFailed $protectFailed uidUnknown $connsUidUnknown"

    companion object {
        private const val SIZE = 27

        fun of(v: LongArray): NativeCaptureStats? {
            if (v.size != SIZE) return null
            return NativeCaptureStats(
                running = v[0] != 0L, exit = NativeCaptureExit.of(v[1].toInt()),
                tunRxPackets = v[2], tunRxBytes = v[3], tunTxPackets = v[4], tunTxBytes = v[5], tunTxErrors = v[6],
                unparsed = v[7], fragments = v[8], ipverDisabled = v[9], tcpWithoutConn = v[10], connsFailed = v[11],
                protectFailed = v[12], connsTcp = v[13], connsUdp = v[14], connsIcmp = v[15], connsActive = v[16],
                pcapPackets = v[17], pcapBytes = v[18], pcapTruncated = v[19], pcapDropped = v[20],
                pcapLastErrno = v[21], wallMinusElapsedNs = v[22], clockUncertaintyNs = v[23],
                startElapsedNs = v[24], stopElapsedNs = v[25], connsUidUnknown = v[26],
            )
        }
    }
}
