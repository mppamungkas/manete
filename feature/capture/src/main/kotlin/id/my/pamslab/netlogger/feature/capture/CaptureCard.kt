package id.my.pamslab.netlogger.feature.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    var mtu by remember { mutableStateOf(controller.settings.nativeMtu) }
    var apps by rememberSaveable { mutableStateOf(controller.settings.appFilter.joinToString(", ")) }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            CaptureSettings.MTU_REALISTIC to "Realistic packets (MTU 1500)",
                            CaptureSettings.MTU_FAST to "Fast (MTU 10 000)",
                        ).forEach { (value, text) ->
                            FilterChip(
                                selected = mtu == value,
                                onClick = { mtu = value; controller.settings.nativeMtu = value },
                                label = { Text(text) },
                            )
                        }
                    }
                    OutlinedTextField(
                        apps, { apps = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Apps (package names, comma separated; empty = all)") },
                    )
                    Text(
                        "NetLogger's own VPN. Captures the IP traffic of apps between each app and the VPN, plus which " +
                            "app owns each connection; no radio signaling, and packet sizes and timing are rebuilt by " +
                            "the forwarder, not the ones on the air. No other VPN at the same time. Adds overhead, more " +
                            "with MTU 1500 (fast links lose up to a quarter of their download speed); speed tests " +
                            "during a capture are flagged.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        controller.settings.appFilter = apps.split(',')
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
