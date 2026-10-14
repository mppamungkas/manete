package id.my.pamslab.netlogger.feature.capture.vpn

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Debug builds only: starts or stops a native capture from adb (milestone 11, step 3).
 *
 * ```
 * adb shell am start -n id.my.pamslab.netlogger/id.my.pamslab.netlogger.feature.capture.vpn.NativeCaptureDebugActivity \
 *     --es cmd start [--es apps com.example.a,com.example.b] [--ei mtu 10000]
 * adb shell am start -n ... --es cmd stop
 * ```
 * The pcap goes to files/captures/native-<time>.pcap, the connections to native-<time>_connections.csv. The VPN consent dialog, if shown, is for the user to answer.
 */
class NativeCaptureDebugActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startCapture() else Log.w(TAG, "native capture: VPN consent denied")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the consent result arrives after a recreation
        when (intent.getStringExtra("cmd")) {
            "start" -> {
                val request = VpnService.prepare(this)
                if (request == null) {
                    startCapture()
                    finish()
                } else {
                    consent.launch(request)
                }
            }
            "stop" -> {
                NativeCaptureService.stop(this)
                finish()
            }
            else -> {
                Log.w(TAG, "NativeCaptureDebugActivity: --es cmd start|stop")
                finish()
            }
        }
    }

    private fun startCapture() {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val dir = File(filesDir, "captures")
        val file = File(dir, "native-$time.pcap")
        val apps = intent.getStringExtra("apps")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        NativeCaptureService.start(this, file, File(dir, "native-${time}_connections.csv"), apps, standalone = true,
            mtu = intent.getIntExtra("mtu", NativeCaptureService.MTU),
        )
    }

    private companion object {
        const val TAG = "NetLogger"
    }
}
