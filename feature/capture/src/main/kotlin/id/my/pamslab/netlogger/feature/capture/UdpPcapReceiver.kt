package id.my.pamslab.netlogger.feature.capture

import java.io.Closeable
import java.io.OutputStream
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Receives PCAPdroid's UDP exporter stream on 127.0.0.1 and writes it as a PCAP file. The stream format (PCAPdroid
 * `UDPDumper`, studied, not copied): the first datagram is the 24-byte PCAP global header, every following datagram
 * is exactly one PCAP record (16-byte record header + packet).
 *
 * Datagrams before the header, or that are not one well-formed record, are counted and dropped, so the file stays
 * valid. For time alignment, the first record of every second is noted in [sync] with its arrival time
 * (elapsedRealtimeNs and wall time) next to PCAPdroid's own record timestamp.
 */
class UdpPcapReceiver(
    private val out: OutputStream,
    private val sync: Writer,
    private val elapsedNs: () -> Long,
    private val wallMs: () -> Long,
    port: Int = 0,
) : Closeable {

    private val socket = DatagramSocket(port, InetAddress.getByName(LOOPBACK))
    val port: Int get() = socket.localPort

    private val records = AtomicLong()
    private val recordBytes = AtomicLong()
    private val rejected = AtomicLong()
    val packets: Long get() = records.get()
    val bytes: Long get() = recordBytes.get()
    val rejectedDatagrams: Long get() = rejected.get()

    @Volatile var headerSeen = false
        private set

    @Volatile var error: String? = null
        private set

    private var order = ByteOrder.LITTLE_ENDIAN
    private var lastSyncNs = Long.MIN_VALUE
    private var lastFlushNs = 0L

    init {
        sync.write("packet,arrivalElapsedNs,arrivalWallMs,pcapTsSec,pcapTsUsec\n")
    }

    // Declared last: the thread may run before any later initializer.
    private val worker = thread(name = "NetLogger-pcap-udp", isDaemon = true) { loop() }

    private fun loop() {
        val buffer = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (!socket.isClosed) {
                packet.setLength(buffer.size)
                socket.receive(packet)
                synchronized(this) { handle(buffer, packet.length) }
            }
        } catch (_: SocketException) {
            // close() ends the loop.
        } catch (e: Exception) {
            error = e.toString()
        }
    }

    private fun handle(data: ByteArray, len: Int) {
        val now = elapsedNs()
        if (!headerSeen) {
            val magic = if (len == GLOBAL_HEADER_BYTES) magicOrder(data) else null
            if (magic == null) {
                rejected.incrementAndGet()
                return
            }
            order = magic
            out.write(data, 0, len)
            headerSeen = true
            return
        }
        if (len < RECORD_HEADER_BYTES) {
            rejected.incrementAndGet()
            return
        }
        val header = ByteBuffer.wrap(data, 0, RECORD_HEADER_BYTES).order(order)
        val tsSec = header.getInt(0).toLong() and 0xFFFFFFFFL
        val tsUsec = header.getInt(4).toLong() and 0xFFFFFFFFL
        val inclLen = header.getInt(8).toLong() and 0xFFFFFFFFL
        if (inclLen != (len - RECORD_HEADER_BYTES).toLong()) {
            rejected.incrementAndGet()
            return
        }
        out.write(data, 0, len)
        val n = records.incrementAndGet()
        recordBytes.addAndGet(len.toLong())
        if (lastSyncNs == Long.MIN_VALUE || now - lastSyncNs >= SYNC_EVERY_NS) {
            lastSyncNs = now
            sync.write("$n,$now,${wallMs()},$tsSec,$tsUsec\n")
        }
        if (now - lastFlushNs >= FLUSH_EVERY_NS) {
            // A killed process loses at most ~1 s of packets.
            lastFlushNs = now
            out.flush()
            sync.flush()
        }
    }

    /** Stops receiving and flushes both files; the caller closes the streams. */
    override fun close() {
        socket.close()
        worker.join(JOIN_TIMEOUT_MS)
        synchronized(this) {
            out.flush()
            sync.flush()
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val GLOBAL_HEADER_BYTES = 24
        const val RECORD_HEADER_BYTES = 16
        // A record is at most 16 + 65535 bytes; anything larger is truncated by the socket and then rejected.
        private const val MAX_DATAGRAM = 70_000
        private const val SYNC_EVERY_NS = 1_000_000_000L
        private const val FLUSH_EVERY_NS = 1_000_000_000L
        private const val JOIN_TIMEOUT_MS = 2_000L

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
