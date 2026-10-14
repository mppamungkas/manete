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
import androidx.compose.material3.OutlinedButton
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
import id.my.pamslab.netlogger.core.model.capture.CaptureMode
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(val controller: CaptureController) : ViewModel()

/**
 * Live screen card for traffic capture: PCAPdroid (Phase 3a) or NetLogger's own VPN (native, Phase 3b). Shown while a session records, or while PCAPdroid may
 * still capture after the session ended. Limits shown to the user: IP traffic of apps only (no radio signaling), no
 * other VPN at the same time, and capture overhead (speed tests during a capture are flagged captureActive).
 */
@Composable
fun CaptureCard(modifier: Modifier = Modifier, viewModel: CaptureViewModel = hiltViewModel()) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val session by controller.openSession.collectAsStateWithLifecycle()
    val pcapdroidMayRun by controller.pcapdroidMayRun.collectAsStateWithLifecycle()
    if (session == null && !state.running && !pcapdroidMayRun) return

    val scope = rememberCoroutineScope()
    val pkg = remember { controller.pcapdroidPackage() }
    var mode by remember { mutableStateOf(controller.settings.mode) }
    var mtu by remember { mutableStateOf(controller.settings.nativeMtu) }
    var apps by rememberSaveable { mutableStateOf(controller.settings.appFilter.joinToString(", ")) }
    val startLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.onStartResult(it.resultCode == Activity.RESULT_OK)
    }
    val stopLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.onStopResult(it.resultCode == Activity.RESULT_OK, it.data)
    }
    fun launchStop() {
        val intent = controller.stopIntent() ?: return
        try {
            stopLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("NetLogger", "PCAPdroid not found", e)
        }
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Traffic capture", style = MaterialTheme.typography.titleSmall)
            when {
                state.running && state.mode == CaptureMode.NATIVE -> {
                    Text("Capturing · native VPN · ${state.fileName}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "%d packets · %.1f MB · %d connections".format(
                            Locale.ROOT, state.packets, state.bytes / 1e6, state.connections ?: 0,
                        ) + (state.droppedPackets?.takeIf { it > 0 }?.let { " · $it not written" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { controller.stopNative() }) { Text("Stop capture") }
                }
                state.running -> {
                    Text("Capturing · PCAPdroid ${state.mode} · ${state.fileName}", style = MaterialTheme.typography.bodySmall)
                    if (state.mode == CaptureMode.TCP) {
                        Text(
                            "NetLogger received %d packets · %.1f MB".format(Locale.ROOT, state.packets, state.bytes / 1e6),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("PCAPdroid writes ${CaptureController.PCAPDROID_DIR}/${state.fileName}", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = ::launchStop) { Text("Stop capture") }
                }
                session == null -> {
                    Text("The session ended. PCAPdroid may still be capturing.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = ::launchStop) { Text("Stop PCAPdroid capture") }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CaptureMode.entries.filter { it != CaptureMode.NATIVE || controller.nativeAvailable }.forEach { m ->
                            FilterChip(
                                selected = m == mode,
                                onClick = { mode = m; controller.settings.mode = m },
                                label = {
                                    Text(
                                        when (m) {
                                            CaptureMode.NATIVE -> "Native (VPN)"
                                            CaptureMode.TCP -> "PCAPdroid → NetLogger"
                                            CaptureMode.FILE -> "PCAPdroid file"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (mode == CaptureMode.NATIVE) {
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
                    }
                    OutlinedTextField(
                        apps, { apps = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Apps (package names, comma separated; empty = all)") },
                    )
                    Text(
                        if (mode == CaptureMode.NATIVE) {
                            "NetLogger's own VPN. Captures the IP traffic of apps between each app and the VPN, plus " +
                                "which app owns each connection; no radio signaling, and packet sizes and timing are " +
                                "rebuilt by the forwarder, not the ones on the air. No other VPN at the same time. " +
                                "Adds overhead, more with MTU 1500 (fast links lose up to a quarter of their download " +
                                "speed); speed tests during a capture are flagged."
                        } else {
                            "Captures the IP traffic of apps only, no radio signaling. Uses Android's VPN slot: no other " +
                                "VPN at the same time. Adds overhead; speed tests during a capture are flagged."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (mode != CaptureMode.NATIVE && pkg == null) {
                        Text(
                            "PCAPdroid is not installed. Install it (F-Droid or Play Store), or use the native mode.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Button(onClick = {
                            controller.settings.appFilter = apps.split(',')
                            scope.launch {
                                // NATIVE: null also when the VPN consent was given before and the capture is starting.
                                val intent = controller.prepareStart() ?: return@launch
                                try {
                                    startLauncher.launch(intent)
                                } catch (e: ActivityNotFoundException) {
                                    Log.w("NetLogger", "start activity not found", e)
                                    controller.onStartResult(false)
                                }
                            }
                        }) { Text("Start capture") }
                    }
                }
            }
            if (!state.running && state.fileName != null) {
                // The last capture: loss is always visible.
                when (state.mode) {
                    CaptureMode.TCP -> {
                        val pcapdroid = state.pcapdroidPackets
                        Text(
                            "Last capture: NetLogger received ${state.packets} packets · PCAPdroid counted ${pcapdroid ?: "–"}" +
                                (pcapdroid?.let { " · missing ${it - state.packets}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    CaptureMode.NATIVE -> state.expectedPackets?.let { expected ->
                        Text(
                            "Last capture: ${state.packets} of $expected packets in the pcap · " +
                                "${state.droppedPackets ?: 0} not written · ${state.connections ?: 0} connections",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> Unit
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
