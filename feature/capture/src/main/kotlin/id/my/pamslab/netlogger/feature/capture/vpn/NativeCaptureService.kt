package id.my.pamslab.netlogger.feature.capture.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import id.my.pamslab.netlogger.core.export.IsoTime
import id.my.pamslab.netlogger.feature.capture.R
import java.io.File
import java.io.IOException
import java.time.ZoneId

/**
 * Native traffic capture (Phase 3b): a VpnService whose tun is read by libnetcapture, which forwards the traffic
 * with zdtun and writes every packet to a pcap file (docs/decisions/002-native-capture.md).
 *
 * Runs in its own process (":vpn", decision D3), so a native crash can't end radio logging. That process writes no
 * database rows; it only writes the pcap under NetLogger's files/captures/.
 *
 * Limits: only IP traffic of apps, between each app and the VPN (no radio signaling, no on-air packets; packet sizes
 * and timing on the tun side are rebuilt by the forwarder); no other VPN at the same time.
 */
class NativeCaptureService : VpnService() {

    private val main = Handler(Looper.getMainLooper())
    private var capture: Running? = null

    private class Running(
        val handle: Long,
        val tun: ParcelFileDescriptor,
        val pcap: File,
        val connections: File?,
        val callbacks: NativeCaptureCallbacks,
    ) {
        lateinit var thread: Thread
        @Volatile var exit: NativeCaptureExit? = null
    }

    private val statsLogger = object : Runnable {
        override fun run() {
            val c = capture ?: return
            NativeCapture.stats(c.handle)?.let { Log.i(TAG, "native capture: ${it.summary()}") }
            main.postDelayed(this, STATS_LOG_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Started with startForegroundService: startForeground comes first, whatever happens next.
                val pcapFile = intent.getStringExtra(EXTRA_PCAP_PATH)?.let(::File)
                if (enterForeground(pcapFile?.name ?: "–") && pcapFile != null) {
                    start(
                        pcapFile,
                        intent.getStringExtra(EXTRA_CONNECTIONS_PATH)?.let(::File),
                        intent.getStringArrayExtra(EXTRA_ALLOWED_APPS)?.toList().orEmpty(),
                    )
                }
            }
            ACTION_STOP -> stop("stop requested")
        }
        if (capture == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Another VPN started, or the user turned ours off in the system settings.
        stop("VPN revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        // Finish the pcap before the process may go away: the loop ends within one select timeout (250 ms).
        capture?.let { c ->
            stop("service destroyed")
            c.thread.join(DESTROY_JOIN_MS)
            c.exit?.let(::onLoopEnded) ?: Log.w(TAG, "native capture loop still running at onDestroy")
        }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun enterForeground(text: String): Boolean = try {
        // The specialUse type exists from Android 14; before, a foreground service needs no type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification(text))
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "native capture: startForeground failed", e)
        false
    }

    private fun start(pcapFile: File, connectionsFile: File?, allowedApps: List<String>) {
        if (capture != null) {
            Log.w(TAG, "native capture already running")
            return
        }
        if (!NativeCapture.available) {
            Log.w(TAG, "native capture unavailable: ${NativeCapture.loadError}")
            return
        }
        val plan = plan()
        val tun = try {
            builder(plan, allowedApps).establish()
        } catch (e: Exception) {
            Log.w(TAG, "native capture: establish failed", e)
            null
        }
        if (tun == null) {
            // Not prepared (no VPN consent) or the system refused.
            Log.w(TAG, "native capture: no tun (VPN consent missing?)")
            return
        }
        val pcapFd = try {
            pcapFile.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                pcapFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
            ).detachFd()
        } catch (e: IOException) {
            Log.w(TAG, "native capture: can't open $pcapFile", e)
            tun.close()
            return
        }
        val handle = NativeCapture.create()
        if (handle == 0L) {
            ParcelFileDescriptor.adoptFd(pcapFd).close()
            tun.close()
            return
        }
        val config = NativeCapture.Config(mtu = MTU, ipv6 = plan.ipv6)
        val callbacks = callbacks(handle, connectionsFile)
        val running = Running(handle, tun, pcapFile, connectionsFile, callbacks)
        running.thread = Thread({
            val exit = NativeCapture.run(handle, callbacks, tun.fd, pcapFd, config)
            running.exit = exit
            main.post { onLoopEnded(exit) }
        }, "netcapture")
        capture = running
        Log.i(
            TAG,
            "native capture started: ${pcapFile.name}, ipv6 ${plan.ipv6}, dns ${plan.dnsServers.map { it.hostAddress }}" +
                (if (plan.dnsFallback) " (fallback)" else "") +
                ", apps ${allowedApps.ifEmpty { listOf("all") }}, ${NativeCapture.version()}",
        )
        running.thread.start()
        main.postDelayed(statsLogger, STATS_LOG_MS)
    }

    /** Asks the loop to end; [onLoopEnded] cleans up. */
    private fun stop(why: String) {
        val c = capture ?: return
        Log.i(TAG, "native capture stopping: $why")
        NativeCapture.stop(c.handle)
    }

    private fun onLoopEnded(exit: NativeCaptureExit) {
        val c = capture ?: return
        capture = null
        main.removeCallbacks(statsLogger)
        val stats = NativeCapture.stats(c.handle)
        NativeCapture.destroy(c.handle)
        c.callbacks.close()
        try {
            c.tun.close()
        } catch (e: IOException) {
            Log.w(TAG, "closing the tun failed", e)
        }
        Log.i(
            TAG,
            "native capture ended ($exit): ${c.pcap.name}, ${c.pcap.length()} bytes" +
                (c.connections?.let { ", ${it.name} ${it.length()} bytes" } ?: "") + ", ${stats?.summary()}",
        )
        stats?.let {
            Log.i(
                TAG,
                "native capture clock: wallMinusElapsedNs ${it.wallMinusElapsedNs} ±${it.clockUncertaintyNs} ns, " +
                    "elapsed ${it.startElapsedNs}..${it.stopElapsedNs}",
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Callbacks for the capture thread; connections.csv opened with its header, if a path was given. */
    private fun callbacks(handle: Long, connectionsFile: File?): NativeCaptureCallbacks {
        val out = connectionsFile?.let { file ->
            try {
                file.parentFile?.mkdirs()
                file.bufferedWriter(Charsets.UTF_8, CSV_BUFFER)
            } catch (e: IOException) {
                Log.w(TAG, "native capture: can't open $file", e)
                null
            }
        }
        // The capture's own clock offset (set before the first packet), so connection times match the pcap.
        var offset: Long? = null
        val csv = out?.let {
            ConnectionsCsv(it, IsoTime(ZoneId.systemDefault()), {
                offset ?: NativeCapture.stats(handle)?.wallMinusElapsedNs?.takeIf { o -> o != 0L }?.also { o -> offset = o }
            }, AppNames(packageManager)::of).apply { header() }
        }
        return NativeCaptureCallbacks(this, getSystemService(ConnectivityManager::class.java), csv, out)
    }

    /** The network the phone used before the VPN (the VPN isn't up yet, so this is mobile data or Wi-Fi). */
    private fun plan(): TunPlan {
        val cm = getSystemService(ConnectivityManager::class.java)
        val props = cm?.activeNetwork?.let(cm::getLinkProperties)
        return TunPlan.of(
            linkAddresses = props?.linkAddresses?.map { it.address }.orEmpty(),
            dnsServers = props?.dnsServers.orEmpty(),
        )
    }

    private fun builder(plan: TunPlan, allowedApps: List<String>): Builder {
        val b = Builder()
            .setSession("NetLogger capture")
            .setMtu(MTU)
            .setBlocking(true)
            // Metered like the network underneath, instead of always metered.
            .setMetered(false)
            .addAddress(TunPlan.IPV4_ADDRESS, TunPlan.IPV4_PREFIX)
        TunPlan.IPV4_ROUTES.forEach { (address, prefix) -> b.addRoute(address, prefix) }
        if (plan.ipv6) {
            b.addAddress(TunPlan.IPV6_ADDRESS, TunPlan.IPV6_PREFIX)
            TunPlan.IPV6_ROUTES.forEach { (address, prefix) -> b.addRoute(address, prefix) }
        }
        plan.dnsServers.forEach(b::addDnsServer)
        allowedApps.forEach { pkg ->
            try {
                b.addAllowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "native capture: app $pkg not installed, not captured")
            }
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            b.setConfigureIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE))
        }
        return b
    }

    private fun notification(fileName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Traffic capture", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while NetLogger captures the phone's IP traffic"
            },
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NativeCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle("NetLogger is capturing traffic")
            .setContentText(fileName)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop capture", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "NetLogger"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 11
        private const val STATS_LOG_MS = 5_000L
        private const val DESTROY_JOIN_MS = 2_000L
        private const val CSV_BUFFER = 64 * 1024

        /** Tun MTU (decision D4): captured packets keep realistic sizes. */
        const val MTU = 1500

        const val ACTION_START = "id.my.pamslab.netlogger.capture.NATIVE_START"
        const val ACTION_STOP = "id.my.pamslab.netlogger.capture.NATIVE_STOP"
        const val EXTRA_PCAP_PATH = "pcap_path"
        const val EXTRA_ALLOWED_APPS = "allowed_apps"
        const val EXTRA_CONNECTIONS_PATH = "connections_path"

        /** Needs VPN consent first: [VpnService.prepare] must return null. */
        fun start(context: Context, pcapFile: File, connectionsFile: File?, allowedApps: List<String>) {
            context.startForegroundService(
                Intent(context, NativeCaptureService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PCAP_PATH, pcapFile.absolutePath)
                    .putExtra(EXTRA_CONNECTIONS_PATH, connectionsFile?.absolutePath)
                    .putExtra(EXTRA_ALLOWED_APPS, allowedApps.toTypedArray()),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NativeCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
