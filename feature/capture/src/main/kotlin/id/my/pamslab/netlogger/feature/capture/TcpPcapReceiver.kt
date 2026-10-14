package id.my.pamslab.netlogger.feature.capture

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Receives PCAPdroid's TCP exporter stream on 127.0.0.1 and writes it to [out] unchanged. The stream (PCAPdroid
 * `TCPDumper`, studied, not copied) is a classic PCAP file: the 24-byte global header, then the records back to back.
 *
 * TCP instead of UDP: the UDP exporter lost ~46 % of the packets of a 10 MB download (moto g45, 2026-10-14); TCP
 * cannot drop, PCAPdroid waits when NetLogger falls behind. NetLogger must not be that bottleneck: one dedicated
 * thread reads through a large buffer and writes through a large buffer, and only parses each record header to count
 * packets and to stop cleanly on a malformed stream. No per-packet database or UI work.
 */
class TcpPcapReceiver(private val out: OutputStream) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
    val port: Int get() = server.localPort

    private val records = AtomicLong()
    private val recordBytes = AtomicLong()
    val packets: Long get() = records.get()

    /** Record bytes written (headers included), without the 24-byte global header. */
    val bytes: Long get() = recordBytes.get()

    @Volatile var headerSeen = false
        private set

    /** Why the stream ended early (malformed data, socket error); null for a clean end. */
    @Volatile var error: String? = null
        private set

    @Volatile private var client: Socket? = null
    private val ended = CountDownLatch(1)

    // Declared last: the thread may run before any later initializer.
    private val worker = thread(name = "NetLogger-pcap-tcp", isDaemon = true) {
        try {
            server.accept().use { socket ->
                client = socket
                server.close() // one connection per capture
                read(DataInputStream(BufferedInputStream(socket.getInputStream(), READ_BUFFER)))
            }
        } catch (_: SocketException) {
            // close() before PCAPdroid connected, or while reading.
        } catch (e: IOException) {
            error = e.toString()
        } finally {
            runCatching { out.flush() }
            ended.countDown()
        }
    }

    private fun read(input: DataInputStream) {
        val global = ByteArray(GLOBAL_HEADER_BYTES)
        try {
            input.readFully(global)
        } catch (_: EOFException) {
            return // connected and closed without data
        }
        val order = magicOrder(global) ?: run {
            error = "not a PCAP stream (magic ${global.take(4).joinToString("") { "%02x".format(it) }})"
            return
        }
        out.write(global)
        headerSeen = true
        val header = ByteArray(RECORD_HEADER_BYTES)
        val view = ByteBuffer.wrap(header).order(order)
        var payload = ByteArray(INITIAL_PAYLOAD)
        while (true) {
            try {
                input.readFully(header)
            } catch (_: EOFException) {
                return // PCAPdroid closed the connection between records: clean end
            }
            val inclLen = view.getInt(8).toLong() and 0xFFFFFFFFL
            if (inclLen > MAX_RECORD) {
                error = "record length $inclLen after packet ${records.get()}: stream out of sync"
                return
            }
            if (payload.size < inclLen) payload = ByteArray(inclLen.toInt())
            try {
                input.readFully(payload, 0, inclLen.toInt())
            } catch (e: EOFException) {
                error = "stream ended inside packet ${records.get() + 1}"
                return
            }
            out.write(header)
            out.write(payload, 0, inclLen.toInt())
            records.incrementAndGet()
            recordBytes.addAndGet(RECORD_HEADER_BYTES + inclLen)
        }
    }

    /** Waits until PCAPdroid closed the stream (after its capture stopped), so no buffered packet is cut off. */
    fun awaitEnd(timeoutMs: Long): Boolean = ended.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        runCatching { server.close() }
        runCatching { client?.close() }
        worker.join(JOIN_TIMEOUT_MS)
        runCatching { out.flush() }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val GLOBAL_HEADER_BYTES = 24
        const val RECORD_HEADER_BYTES = 16
        private const val READ_BUFFER = 1 shl 20
        private const val INITIAL_PAYLOAD = 65_536
        /** PCAPdroid's snaplen is at most 65535; anything far above means the stream lost sync. */
        private const val MAX_RECORD = 262_144L
        private const val JOIN_TIMEOUT_MS = 3_000L

        /** Byte order of a PCAP global header (µs or ns magic), or null when [data] does not start with one. */
        fun magicOrder(data: ByteArray): ByteOrder? {
            val le = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val be = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            return when {
                le == MAGIC_US || le == MAGIC_NS -> ByteOrder.LITTLE_ENDIAN
                be == MAGIC_US || be == MAGIC_NS -> ByteOrder.BIG_ENDIAN
                else -> null
            }
        }

        private const val MAGIC_US = 0xA1B2C3D4.toInt()
        private const val MAGIC_NS = 0xA1B23C4D.toInt()
    }
}
