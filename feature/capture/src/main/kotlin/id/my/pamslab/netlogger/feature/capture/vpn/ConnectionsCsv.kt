package id.my.pamslab.netlogger.feature.capture.vpn

import id.my.pamslab.netlogger.core.export.CsvWriter
import id.my.pamslab.netlogger.core.export.IsoTime
import java.net.InetAddress

/** One forwarded connection of a native capture (capture_conn_t). "Out" = app → network, "in" = network → app. */
data class ConnectionRecord(
    val ipVersion: Int,
    val protocol: Int,
    val srcIp: InetAddress,
    val srcPort: Int,
    val dstIp: InetAddress,
    val dstPort: Int,
    /** Owning app's uid, null when unknown (always for ICMP). */
    val uid: Int?,
    val firstElapsedNs: Long,
    val lastElapsedNs: Long,
    val packetsOut: Long,
    val bytesOut: Long,
    val packetsIn: Long,
    val bytesIn: Long,
    val status: String,
    val socketError: Int,
) {
    val protocolName: String get() = when (protocol) {
        PROTO_TCP -> "TCP"
        PROTO_UDP -> "UDP"
        PROTO_ICMP -> "ICMP"
        else -> protocol.toString()
    }

    companion object {
        const val PROTO_ICMP = 1
        const val PROTO_TCP = 6
        const val PROTO_UDP = 17
    }
}

/**
 * connections.csv of a native capture (decision 002, D6): one row per connection, written when it ends (also every
 * connection still open when the capture stops). Columns are defined in docs/columns.md.
 *
 * [wallMinusElapsedNs] is the capture's clock offset (the one used for the pcap timestamps), so `firstSeen` and the
 * pcap agree exactly. [app] maps a uid to its package name(s).
 */
class ConnectionsCsv(
    out: Appendable,
    private val time: IsoTime,
    private val wallMinusElapsedNs: () -> Long?,
    private val app: (Int) -> String?,
) {
    private val csv = CsvWriter(out)

    fun header() = csv.header(COLUMNS)

    fun row(r: ConnectionRecord) {
        val offset = wallMinusElapsedNs()
        fun wallMs(elapsedNs: Long) = offset?.let { Math.floorDiv(elapsedNs + it, 1_000_000L) }
        csv.row(
            listOf(
                r.protocolName, r.ipVersion,
                r.srcIp.hostAddress, r.srcPort, r.dstIp.hostAddress, r.dstPort.takeIf { r.protocol != ConnectionRecord.PROTO_ICMP },
                r.uid, r.uid?.let(app),
                time.format(wallMs(r.firstElapsedNs)), time.format(wallMs(r.lastElapsedNs)),
                r.firstElapsedNs, r.lastElapsedNs, (r.lastElapsedNs - r.firstElapsedNs) / 1_000_000,
                r.packetsOut, r.bytesOut, r.packetsIn, r.bytesIn,
                r.status, r.socketError.takeIf { it != 0 },
            ),
        )
    }

    companion object {
        val COLUMNS = listOf(
            "protocol", "ipVersion", "srcIp", "srcPort", "dstIp", "dstPort", "uid", "app",
            "firstSeen", "lastSeen", "firstSeenElapsedNs", "lastSeenElapsedNs", "durationMs",
            "packetsOut", "bytesOut", "packetsIn", "bytesIn", "status", "socketError",
        )
    }
}
