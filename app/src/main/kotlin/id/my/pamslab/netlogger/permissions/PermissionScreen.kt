package id.my.pamslab.netlogger.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Rationale screen shown before any runtime permission request. */
@Composable
fun PermissionScreen(
    viewModel: PermissionViewModel,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        viewModel.onPermissionResult(results) { permission ->
            activity?.shouldShowRequestPermissionRationale(permission) ?: true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineMedium)
        Text(
            "NetLogger records radio measurements for research. It only uses public Android APIs " +
                "and keeps all data on the device until you export it.",
            style = MaterialTheme.typography.bodyMedium,
        )

        state.groups.filter { it.status != PermissionStatus.NOT_APPLICABLE }.forEach { PermissionCard(it) }

        val toRequest = viewModel.permissionsToRequest()
        if (toRequest.isNotEmpty()) {
            Button(onClick = { launcher.launch(toRequest.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Grant permissions")
            }
        }
        if (state.anyPermanentlyDenied) {
            OutlinedButton(onClick = { context.openAppSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text("Open app settings")
            }
        }
        TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.canLog) "Continue" else "Continue without logging")
        }
    }
}

@Composable
private fun PermissionCard(groupState: GroupState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(groupState.group.title, style = MaterialTheme.typography.titleMedium)
            Text(groupState.group.rationale, style = MaterialTheme.typography.bodySmall)
            Text(
                text = groupState.status.label(),
                style = MaterialTheme.typography.labelLarge,
                color = if (groupState.status == PermissionStatus.GRANTED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

private fun PermissionStatus.label(): String = when (this) {
    PermissionStatus.GRANTED -> "Granted"
    PermissionStatus.APPROXIMATE_ONLY -> "Approximate only: choose \"Precise\" to log cells and GPS"
    PermissionStatus.DENIED -> "Not granted"
    PermissionStatus.PERMANENTLY_DENIED -> "Denied: enable it in app settings"
    PermissionStatus.NOT_APPLICABLE -> "Not needed on this Android version"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
