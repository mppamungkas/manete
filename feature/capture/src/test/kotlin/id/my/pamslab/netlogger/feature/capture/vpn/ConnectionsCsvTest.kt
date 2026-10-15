package id.my.pamslab.netlogger.feature.capture.vpn

import id.my.pamslab.netlogger.core.export.IsoTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.time.ZoneId

class ConnectionsCsvTest {

    private val jakarta = IsoTime(ZoneId.of("Asia/Jakarta"))

    // 2026-10-14T22:15:58.000Z = 2026-10-15T05:15:58.000+07:00 at elapsedRealtimeNs 143,981,000,000,000.
    private val offsetNs = 1_792_016_158_000_000_000L - 143_981_000_000_000L

    private fun record(protocol: Int, uid: Int?, dstPort: Int = 443, error: Int = 0) = ConnectionRecord(
        ipVersion = 4, protocol = protocol,
        srcIp = InetAddress.getByName("10.111.222.1"), srcPort = 46908,
        dstIp = InetAddress.getByName("172.66.0.218"), dstPort = dstPort, uid = uid,
        firstElapsedNs = 143_981_000_000_000L, lastElapsedNs = 143_982_500_400_000L,
        packetsOut = 12, bytesOut = 1_500, packetsIn = 40, bytesIn = 58_000, status = "CLOSED", socketError = error,
    )

    private fun csv(vararg records: ConnectionRecord, offset: Long? = offsetNs): List<String> {
        val out = StringBuilder()
        val apps = mapOf(10_123 to "com.android.chrome", 1_000 to "android.uid.system:1000")
        ConnectionsCsv(out, jakarta, { offset }, apps::get).apply {
            header()
            records.forEach(::row)
        }
        return out.split("\r\n").dropLast(1)
    }

    @Test
    fun `header matches the documented columns`() {
        assertEquals(ConnectionsCsv.COLUMNS.joinToString(","), csv()[0])
    }

    @Test
    fun `TCP connection with its app and both time bases`() {
        assertEquals(
            "TCP,4,10.111.222.1,46908,172.66.0.218,443,10123,com.android.chrome," +
                "2026-10-15T05:15:58.000+07:00,2026-10-15T05:15:59.500+07:00,143981000000000,143982500400000,1500," +
                "12,1500,40,58000,CLOSED,",
            csv(record(ConnectionRecord.PROTO_TCP, 10_123))[1],
        )
    }

    @Test
    fun `unknown uid and app are empty, ICMP has no destination port, socket error kept`() {
        val row = csv(record(ConnectionRecord.PROTO_ICMP, null, dstPort = 0, error = 113))[1].split(',')
        assertEquals("ICMP", row[0])
        assertEquals("", row[5])
        assertEquals("", row[6])
        assertEquals("", row[7])
        assertEquals("113", row[18])
    }

    @Test
    fun `uid without a known app keeps the uid`() {
        val row = csv(record(ConnectionRecord.PROTO_UDP, 99_999))[1].split(',')
        assertEquals("UDP", row[0])
        assertEquals("99999", row[6])
        assertEquals("", row[7])
    }

    @Test
    fun `no clock offset yet - wall times empty, elapsed kept`() {
        val row = csv(record(ConnectionRecord.PROTO_TCP, 1_000), offset = null)[1].split(',')
        assertEquals("android.uid.system:1000", row[7])
        assertEquals("", row[8])
        assertEquals("", row[9])
        assertEquals("143981000000000", row[10])
    }
}
