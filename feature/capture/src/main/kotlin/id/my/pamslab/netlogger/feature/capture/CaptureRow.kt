package id.my.pamslab.netlogger.feature.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(val controller: CaptureController) : ViewModel()

/**
 * Live screen row for traffic capture with NetLogger's own VPN (Phase 3b), inside the Live data card (decision 004).
 * Shown while a session records, or while a capture is still ending. The limits (IP traffic of apps only, between each
 * app and the VPN; no other VPN at the same time; capture overhead, speed tests flagged captureActive) are explained in
 * Settings › How to use. [label] is the row's label column, so it lines up with the other Live rows.
 */
@Composable
fun CaptureRow(label: @Composable () -> Unit, modifier: Modifier = Modifier, viewModel: CaptureViewModel = hiltViewModel()) {
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
    val small = MaterialTheme.typography.bodySmall

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        label()
        Column(Modifier.weight(1f)) {
            when {
                state.running -> {
                    Text(stringResource(R.string.capture_on), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "%d packets · %.1f MB · %d connections".format(
                            Locale.ROOT, state.packets, state.bytes / 1e6, state.connections ?: 0,
                        ) + (state.droppedPackets?.takeIf { it > 0 }?.let { " · $it not written" } ?: ""),
                        style = small,
                    )
                }
                !controller.available -> Text(stringResource(R.string.capture_unavailable), style = small)
                else -> {
                    Text(stringResource(R.string.capture_off), style = MaterialTheme.typography.bodyMedium)
                    Text(captureSummary(allApps, selectedApps.size, mtu), style = small)
                }
            }
            if (!state.running) {
                // The last capture: loss is always visible.
                state.expectedPackets?.let { expected ->
                    Text(
                        "Last: ${state.packets} of $expected packets · ${state.droppedPackets ?: 0} not written",
                        style = small,
                    )
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = small) }
        }
        when {
            state.running -> OutlinedButton(onClick = { controller.stopNative() }) { Text(stringResource(R.string.capture_stop)) }
            controller.available -> OutlinedButton(onClick = {
                scope.launch {
                    // null also when the VPN consent was given before and the capture is starting.
                    val intent = controller.prepareStart() ?: return@launch
                    try {
                        consentLauncher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        CaughtErrors.caught("VPN consent activity not found", e)
                        controller.onStartResult(false)
                    }
                }
            }) { Text(stringResource(R.string.capture_start)) }
        }
    }
}

/** One line on the Live capture row: what a capture started now would record (decisions 003, 004). */
internal fun captureSummary(allApps: Boolean, selectedApps: Int, mtu: Int): String {
    val apps = when {
        allApps -> "All apps"
        selectedApps == 0 -> "All apps (no app selected)"
        selectedApps == 1 -> "1 selected app"
        else -> "$selectedApps selected apps"
    }
    // "10 000" as in decision 002; 1500 stays without a space.
    val mtuText = if (mtu >= 10_000) "${mtu / 1000} %03d".format(Locale.ROOT, mtu % 1000) else mtu.toString()
    return "$apps · MTU $mtuText"
}
