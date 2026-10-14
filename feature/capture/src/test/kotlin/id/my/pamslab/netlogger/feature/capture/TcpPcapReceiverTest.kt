package id.my.pamslab.netlogger.feature.capture

import id.my.pamslab.netlogger.core.model.Clock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streams data as PCAPdroid's TCP exporter does: the global header, then the records back to back. */
class TcpPcapReceiverTest {

    private val out = ByteArrayOutputStream()

    private fun globalHeader(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0xA1B2C3D4.toInt()).putShort(2).putShort(4).putInt(0).putInt(0).putInt(32768).putInt(101).array()

    private fun record(tsSec: Int, payload: Int, inclLen: Int = payload): ByteArray =
        ByteBuffer.allocate(16 + payload).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(tsSec).putInt(250_000).putInt(inclLen).putInt(payload)
            .put(ByteArray(payload) { (it % 251).toByte() }).array()

    private fun stream(r: TcpPcapReceiver, vararg chunks: ByteArray) = Socket("127.0.0.1", r.port).use { s ->
        // Arbitrary write boundaries, as TCP may split or merge them.
        val all = chunks.fold(ByteArray(0)) { a, b -> a + b }
        all.toList().chunked(777).forEach { s.getOutputStream().write(it.toByteArray()) }
    }

    @Test
    fun `writes the stream unchanged and counts records, also when split across reads`() {
        val r = TcpPcapReceiver(out)
        val records = (0 until 500).map { record(1_791_968_546 + it / 100, 40 + it % 1_400) }
        stream(r, globalHeader(), *records.toTypedArray())
        assertTrue(r.awaitEnd(3_000))
        r.close()
        assertTrue(r.headerSeen)
        assertNull(r.error)
        assertEquals(500, r.packets)
        assertEquals(records.sumOf { it.size }.toLong(), r.bytes)
        assertArrayEquals(records.fold(globalHeader()) { a, b -> a + b }, out.toByteArray())
    }

    @Test
    fun `10 MB burst - nothing lost`() {
        val r = TcpPcapReceiver(out)
        val records = List(7_500) { record(1, 1_400) } // ~10.6 MB
        stream(r, globalHeader(), *records.toTypedArray())
        assertTrue(r.awaitEnd(10_000))
        r.close()
        assertEquals(7_500, r.packets)
        assertEquals(24 + records.sumOf { it.size }, out.size())
    }

    @Test
    fun `a cut record or a stream out of sync ends with an error, the file keeps whole records only`() {
        val cut = TcpPcapReceiver(out)
        val whole = record(1, 100)
        stream(cut, globalHeader(), whole, record(2, 100).copyOf(60))
        assertTrue(cut.awaitEnd(3_000))
        cut.close()
        assertEquals(1, cut.packets)
        assertEquals("stream ended inside packet 2", cut.error)
        assertEquals(24 + whole.size, out.size())

        val garbage = TcpPcapReceiver(ByteArrayOutputStream())
        stream(garbage, globalHeader(), record(1, 10, inclLen = 1_000_000))
        assertTrue(garbage.awaitEnd(3_000))
        garbage.close()
        assertTrue(garbage.error!!.contains("out of sync"))
    }

    @Test
    fun `not a PCAP stream`() {
        val r = TcpPcapReceiver(out)
        stream(r, ByteArray(24) { 0x41 })
        assertTrue(r.awaitEnd(3_000))
        r.close()
        assertFalse(r.headerSeen)
        assertTrue(r.error!!.startsWith("not a PCAP stream"))
        assertEquals(0, out.size())
    }

    @Test
    fun `close before PCAPdroid connected does not hang`() {
        val r = TcpPcapReceiver(out)
        r.close()
        assertTrue(r.awaitEnd(1_000))
        assertNull(r.error)
    }

    @Test
    fun `clock offset maps a PCAP wall-clock timestamp onto elapsedRealtimeNs`() {
        var e = 96_370_000_000_000L
        val clock = object : Clock {
            override fun wallTimeMs() = 1_791_968_546_950L
            override fun elapsedRealtimeNs() = e.also { e += 2_000 }
        }
        val sync = ClockSync.now(clock)
        assertEquals("midpoint of the two reads", 96_370_000_001_000L, sync.elapsedNs)
        val pcapTsNs = 1_791_968_547_950L * 1_000_000 // one second later on the wall clock
        assertEquals(sync.elapsedNs + 1_000_000_000L, pcapTsNs - sync.wallMinusElapsedNs)
    }
}
