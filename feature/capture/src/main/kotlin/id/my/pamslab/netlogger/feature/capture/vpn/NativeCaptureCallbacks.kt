package id.my.pamslab.netlogger.feature.capture.vpn

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.util.Log
import java.io.Writer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Called by libnetcapture on the capture thread (netcapture_jni.c looks the methods up by name and signature: keep
 * them in sync). An exception thrown here is cleared in native code and counts as a failure.
 */
class NativeCaptureCallbacks(
    private val service: VpnService,
    private val connectivity: ConnectivityManager?,
    private val connections: ConnectionsCsv?,
    private val connectionsOut: Writer?,
) {
    /** Excludes a forwarding socket from the VPN. */
    fun protect(socket: Int): Boolean = service.protect(socket)

    /**
     * Owning app's uid of a new TCP/UDP connection (public API, works only for the active VPN app), or -1.
     * [src] is the app's side (the tun address).
     */
    fun connectionOwner(ipVersion: Int, protocol: Int, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Int {
        val cm = connectivity ?: return -1
        return try {
            cm.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByAddress(src), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dst), dstPort),
            ).takeIf { it >= 0 } ?: -1
        } catch (e: Exception) {
            // SecurityException if we are not the active VPN any more; IllegalArgumentException for other protocols.
            Log.w(TAG, "getConnectionOwnerUid failed: ${e.message}")
            -1
        }
    }

    private var lastFlushNs = 0L

    fun connectionClosed(
        ipVersion: Int, protocol: Int, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int, uid: Int,
        firstElapsedNs: Long, lastElapsedNs: Long, packetsOut: Long, bytesOut: Long, packetsIn: Long, bytesIn: Long,
        status: String, socketError: Int,
    ) {
        val csv = connections ?: return
        try {
            csv.row(
                ConnectionRecord(
                    ipVersion, protocol, InetAddress.getByAddress(src), srcPort, InetAddress.getByAddress(dst), dstPort,
                    uid.takeIf { it >= 0 }, firstElapsedNs, lastElapsedNs, packetsOut, bytesOut, packetsIn, bytesIn,
                    status, socketError,
                ),
            )
            // Like the pcap: if ":vpn" dies, at most about a second of rows is lost.
            val now = System.nanoTime()
            if (now - lastFlushNs >= FLUSH_INTERVAL_NS) {
                connectionsOut?.flush()
                lastFlushNs = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "connections.csv row failed", e)
        }
    }

    /** Writes out connections.csv (after the capture thread ended). */
    fun close() {
        try {
            connectionsOut?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing connections.csv failed", e)
        }
    }

    private companion object {
        const val TAG = "NetLogger"
        const val FLUSH_INTERVAL_NS = 1_000_000_000L
    }
}

/** uid → package name(s), cached. Shared uids list every package, joined by "|"; system uids get their uid name. */
class AppNames(private val pm: PackageManager) {
    private val cache = ConcurrentHashMap<Int, String>()

    fun of(uid: Int): String? = cache[uid] ?: lookup(uid)?.also { cache[uid] = it }

    private fun lookup(uid: Int): String? = try {
        pm.getPackagesForUid(uid)?.sorted()?.joinToString("|")?.takeIf { it.isNotEmpty() } ?: pm.getNameForUid(uid)
    } catch (e: Exception) {
        null
    }
}
