package id.my.pamslab.netlogger.feature.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sends datagrams as PCAPdroid's UDP exporter does: global header first, then one record per datagram. */
class UdpPcapReceiverTest {

    private val out = ByteArrayOutputStream()
    private val sync = StringWriter()
    private var nowNs = 5_000_000_000L
    private val sender = DatagramSocket()

    private fun receiver() = UdpPcapReceiver(out, sync, { nowNs }, { 1_791_968_546_950L + nowNs / 1_000_000 })

    private fun send(r: UdpPcapReceiver, bytes: ByteArray) =
        sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), r.port))

    private fun globalHeader(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0xA1B2C3D4.toInt()).putShort(2).putShort(4).putInt(0).putInt(0).putInt(65535).putInt(101).array()

    /** A record with [payload] bytes; [inclLen] may lie to test rejection. */
    private fun record(tsSec: Int, payload: Int, inclLen: Int = payload): ByteArray =
        ByteBuffer.allocate(16 + payload).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(tsSec).putInt(250_000).putInt(inclLen).putInt(payload)
            .put(ByteArray(payload) { (it % 251).toByte() }).array()

    private fun UdpPcapReceiver.awaitTotal(datagrams: Long) {
        val deadline = System.currentTimeMillis() + 3_000
        while (packets + rejectedDatagrams + (if (headerSeen) 1 else 0) < datagrams && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `writes the header and valid records byte for byte, rejects malformed ones`() {
        val r = receiver()
        val header = globalHeader()
        val a = record(1_791_968_546, 60)
        val bad = record(1_791_968_546, 40, inclLen = 41)
        val b = record(1_791_968_547, 1_400)
        send(r, header); Thread.sleep(50)
        send(r, a); send(r, bad); send(r, b)
        r.awaitTotal(4)
        r.close()

        assertTrue(r.headerSeen)
        assertEquals(2, r.packets)
        assertEquals(1, r.rejectedDatagrams)
        assertEquals((a.size + b.size).toLong(), r.bytes)
        assertArrayEquals(header + a + b, out.toByteArray())
    }

    @Test
    fun `datagrams before the global header are dropped, so the file stays valid`() {
        val r = receiver()
        send(r, record(1, 20))
        r.awaitTotal(1)
        assertFalse(r.headerSeen)
        send(r, globalHeader())
        send(r, record(2, 20))
        r.awaitTotal(3)
        r.close()
        assertEquals(1, r.rejectedDatagrams)
        assertEquals(1, r.packets)
        assertEquals(24 + 36, out.size())
    }

    @Test
    fun `sync rows - the first record of every second, with arrival time and PCAPdroid's timestamp`() {
        val r = receiver()
        send(r, globalHeader()); Thread.sleep(50)
        send(r, record(100, 10)); r.awaitTotal(2)
        nowNs += 400_000_000 // same second: no row
        send(r, record(100, 10)); r.awaitTotal(3)
        nowNs += 700_000_000
        send(r, record(101, 10)); r.awaitTotal(4)
        r.close()
        val lines = sync.toString().trim().lines()
        assertEquals("packet,arrivalElapsedNs,arrivalWallMs,pcapTsSec,pcapTsUsec", lines[0])
        assertEquals(listOf("1,5000000000,1791968551950,100,250000", "3,6100000000,1791968553050,101,250000"), lines.drop(1))
    }

    @Test
    fun `magic number in either byte order, micro- or nanosecond`() {
        assertEquals(ByteOrder.LITTLE_ENDIAN, UdpPcapReceiver.magicOrder(globalHeader()))
        val be = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).putInt(0xA1B23C4D.toInt()).array()
        assertEquals(ByteOrder.BIG_ENDIAN, UdpPcapReceiver.magicOrder(be))
        assertEquals(null, UdpPcapReceiver.magicOrder(ByteArray(24)))
    }
}
