package id.my.pamslab.netlogger.feature.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(val controller: CaptureController) : ViewModel()

/**
 * Live screen card for traffic capture with NetLogger's own VPN (Phase 3b). Shown while a session records, or while
 * a capture is still ending. Limits shown to the user: IP traffic of apps only, between each app and the VPN (no
 * radio signaling, packet sizes and timing rebuilt by the forwarder), no other VPN at the same time, and capture
 * overhead (speed tests during a capture are flagged captureActive).
 */
@Composable
fun CaptureCard(modifier: Modifier = Modifier, viewModel: CaptureViewModel = hiltViewModel()) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val session by controller.openSession.collectAsStateWithLifecycle()
    if (session == null && !state.running) return

    val scope = rememberCoroutineScope()
    val allApps by controller.settings.allApps.collectAsStateWithLifecycle()
    val selectedApps by controller.settings.selectedApps.collectAsStateWithLifecycle()
    val mtu by controller.settings.nativeMtu.collectAsStateWithLifecycle()
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.onStartResult(it.resultCode == Activity.RESULT_OK)
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Traffic capture", style = MaterialTheme.typography.titleSmall)
            when {
                state.running -> {
                    Text("Capturing · ${state.fileName}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "%d packets · %.1f MB · %d connections".format(
                            Locale.ROOT, state.packets, state.bytes / 1e6, state.connections ?: 0,
                        ) + (state.droppedPackets?.takeIf { it > 0 }?.let { " · $it not written" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { controller.stopNative() }) { Text("Stop capture") }
                }
                !controller.available -> Text(
                    "Traffic capture is not available on this phone (the native library could not be loaded).",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    Text(captureSummary(allApps, selectedApps.size, mtu), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "NetLogger's own VPN. Captures the IP traffic of apps between each app and the VPN, plus which " +
                            "app owns each connection; no radio signaling, and packet sizes and timing are rebuilt by " +
                            "the forwarder, not the ones on the air. No other VPN at the same time. Adds overhead; " +
                            "speed tests during a capture are flagged.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        scope.launch {
                            // null also when the VPN consent was given before and the capture is starting.
                            val intent = controller.prepareStart() ?: return@launch
                            try {
                                consentLauncher.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                Log.w("NetLogger", "VPN consent activity not found", e)
                                controller.onStartResult(false)
                            }
                        }
                    }) { Text("Start capture") }
                }
            }
            if (!state.running) {
                // The last capture: loss is always visible.
                state.expectedPackets?.let { expected ->
                    Text(
                        "Last capture: ${state.packets} of $expected packets in the pcap · " +
                            "${state.droppedPackets ?: 0} not written · ${state.connections ?: 0} connections",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** One line on the Live card: what a capture started now would record (decision 003). */
internal fun captureSummary(allApps: Boolean, selectedApps: Int, mtu: Int): String {
    val apps = when {
        allApps -> "All apps"
        selectedApps == 0 -> "All apps (no app selected)"
        selectedApps == 1 -> "1 selected app"
        else -> "$selectedApps selected apps"
    }
    // "10 000" as in decision 002; 1500 stays without a space.
    val mtuText = if (mtu >= 10_000) "${mtu / 1000} %03d".format(Locale.ROOT, mtu % 1000) else mtu.toString()
    return "$apps · MTU $mtuText · change in Settings"
}
