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
 * Live screen card for PCAPdroid traffic capture (Phase 3a). Shown while a session records, or while PCAPdroid may
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
            Text("Traffic capture (PCAPdroid)", style = MaterialTheme.typography.titleSmall)
            when {
                pkg == null -> Text(
                    "PCAPdroid is not installed. Install it (F-Droid or Play Store) to capture the phone's IP traffic " +
                        "with this session.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.running -> {
                    Text("Capturing · ${state.mode} · ${state.fileName}", style = MaterialTheme.typography.bodySmall)
                    if (state.mode == CaptureMode.UDP) {
                        Text(
                            "%d packets · %.1f MB".format(Locale.ROOT, state.packets, state.bytes / 1e6) +
                                if (state.rejectedDatagrams > 0) " · ${state.rejectedDatagrams} rejected" else "",
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
                        CaptureMode.entries.forEach { m ->
                            FilterChip(
                                selected = m == mode,
                                onClick = { mode = m; controller.settings.mode = m },
                                label = { Text(if (m == CaptureMode.UDP) "Into NetLogger" else "PCAPdroid file") },
                            )
                        }
                    }
                    OutlinedTextField(
                        apps, { apps = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Apps (package names, comma separated; empty = all)") },
                    )
                    Text(
                        "Captures the IP traffic of apps only, no radio signaling. Uses Android's VPN slot: no other VPN at " +
                            "the same time. Adds overhead; speed tests during a capture are flagged.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        controller.settings.appFilter = apps.split(',')
                        scope.launch {
                            val intent = controller.prepareStart() ?: return@launch
                            try {
                                startLauncher.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                Log.w("NetLogger", "PCAPdroid not found", e)
                                controller.onStartResult(false)
                            }
                        }
                    }) { Text("Start capture") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
