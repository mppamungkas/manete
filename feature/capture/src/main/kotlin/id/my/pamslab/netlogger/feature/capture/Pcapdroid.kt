package id.my.pamslab.netlogger.feature.capture

import android.content.Intent
import android.content.pm.PackageManager
import id.my.pamslab.netlogger.core.model.capture.CaptureMode

/**
 * PCAPdroid's app API, exactly as documented in its docs/app_api.md: the CaptureCtrl activity, started with
 * startActivityForResult, with string extras (the API parses ints and booleans from strings too).
 */
object Pcapdroid {
    const val PACKAGE = "com.emanuelef.remote_capture"

    /** The beta (debug) build has its own package but the same activity class. */
    const val DEBUG_PACKAGE = "com.emanuelef.remote_capture.debug"
    const val CTRL_ACTIVITY = "com.emanuelef.remote_capture.activities.CaptureCtrl"

    /** Broadcast sent to [CaptureStatusReceiver] when the capture stops (extra `running` = false). */
    const val ACTION_CAPTURE_STATUS = "com.emanuelef.remote_capture.CaptureStatus"

    /** `pcap_name` needs PCAPdroid 62+, `collector_host` 92+ (older ones read `collector_ip_address`). */
    const val MIN_VERSION_CODE_PCAP_NAME = 62

    /** Extras of a `start` request. Free features only: no PCAPNG, no TLS decryption, no dump extensions. */
    fun startExtras(mode: CaptureMode, udpPort: Int?, pcapName: String?, appFilter: List<String>, receiverClass: String): Map<String, String> {
        val extras = linkedMapOf(
            "action" to "start",
            "broadcast_receiver" to receiverClass,
            "pcapng_format" to "false",
            "dump_extensions" to "false",
        )
        when (mode) {
            CaptureMode.UDP -> {
                requireNotNull(udpPort)
                extras["pcap_dump_mode"] = "udp_exporter"
                extras["collector_host"] = UdpPcapReceiver.LOOPBACK
                extras["collector_ip_address"] = UdpPcapReceiver.LOOPBACK // deprecated alias for versions < 92
                extras["collector_port"] = udpPort.toString()
            }
            CaptureMode.FILE -> {
                extras["pcap_dump_mode"] = "pcap_file"
                extras["pcap_name"] = requireNotNull(pcapName)
            }
        }
        val apps = appFilter.map(String::trim).filter(String::isNotEmpty)
        if (apps.isNotEmpty()) extras["app_filter"] = apps.joinToString(",")
        return extras
    }

    val stopExtras: Map<String, String> = mapOf("action" to "stop")
    val statusExtras: Map<String, String> = mapOf("action" to "get_status")

    fun intent(pkg: String, extras: Map<String, String>): Intent =
        Intent(Intent.ACTION_VIEW).setClassName(pkg, CTRL_ACTIVITY).apply { extras.forEach { (k, v) -> putExtra(k, v) } }

    /** The installed PCAPdroid package (release first), or null. Needs the `<queries>` entry in the manifest. */
    fun installedPackage(pm: PackageManager): String? = listOf(PACKAGE, DEBUG_PACKAGE).firstOrNull { pkg ->
        try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Capture stats from a `stop` / `get_status` result or the CaptureStatus broadcast (PCAPdroid 50+). */
    fun stats(intent: Intent?): String? {
        val extras = intent?.extras ?: return null
        if (!extras.containsKey("pkts_sent")) return null
        return "pkts_sent=${extras.getInt("pkts_sent")} pkts_rcvd=${extras.getInt("pkts_rcvd")} " +
            "bytes_sent=${extras.getLong("bytes_sent")} bytes_rcvd=${extras.getLong("bytes_rcvd")} " +
            "bytes_dumped=${extras.getLong("bytes_dumped")}"
    }
}
