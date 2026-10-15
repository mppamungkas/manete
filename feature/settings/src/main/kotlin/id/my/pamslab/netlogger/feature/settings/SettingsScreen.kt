package id.my.pamslab.netlogger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import id.my.pamslab.netlogger.feature.settings.navigation.LoggingSettingsRoute
import id.my.pamslab.netlogger.feature.settings.navigation.SpeedTestSettingsRoute
import java.net.URI

/** The Settings list: one entry per page, each with its current value (decision 003). */
@Composable
internal fun SettingsScreen(onOpen: (Any) -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val speedTest by viewModel.speedTest.collectAsStateWithLifecycle()
    val publicTarget by viewModel.publicTarget.collectAsStateWithLifecycle()
    val interval by viewModel.intervalMs.collectAsStateWithLifecycle()
    val scenario by viewModel.defaultScenario.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.settings_section_measurement), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SettingsEntry(
            stringResource(R.string.settings_logging),
            stringResource(
                R.string.settings_logging_summary,
                interval.toInt(),
                stringResource(scenario?.labelRes() ?: R.string.logging_scenario_last),
            ),
        ) { onOpen(LoggingSettingsRoute) }
        val host = remember(speedTest) { runCatching { URI(speedTest.downloadUrl.trim()).host }.getOrNull() }
        val server = if (speedTest.isComplete) stringResource(R.string.settings_speed_test_summary_server, host ?: speedTest.downloadUrl)
            else stringResource(R.string.settings_speed_test_summary_none)
        SettingsEntry(
            stringResource(R.string.settings_speed_test),
            if (publicTarget) stringResource(R.string.settings_speed_test_summary_public, server) else server,
        ) { onOpen(SpeedTestSettingsRoute) }
    }
}

/** Speed test server and the public secondary target. */
@Composable
internal fun SpeedTestSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by viewModel.speedTest.collectAsStateWithLifecycle()
    val publicTarget by viewModel.publicTarget.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_speed_test), onBack) {
        SpeedTestServerCard(saved, viewModel::save, viewModel::cleartextBlocked)
        PublicTargetCard(publicTarget, viewModel::setPublicTarget)
    }
}

@Composable
private fun SpeedTestServerCard(
    saved: SpeedTestConfig,
    onSave: (String, String, String) -> Unit,
    cleartextBlocked: (List<String>) -> List<String>,
) {
    var base by rememberSaveable { mutableStateOf("") }
    var ping by rememberSaveable(saved) { mutableStateOf(saved.pingUrl) }
    var download by rememberSaveable(saved) { mutableStateOf(saved.downloadUrl) }
    var upload by rememberSaveable(saved) { mutableStateOf(saved.uploadUrl) }
    val changed = remember(saved, ping, download, upload) {
        ping.trim() != saved.pingUrl || download.trim() != saved.downloadUrl || upload.trim() != saved.uploadUrl
    }
    val url = KeyboardOptions(keyboardType = KeyboardType.Uri)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Speed test server", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your own server. A LibreSpeed backend: enter its base URL and fill the three URLs. Any web server: " +
                    "ping = a small file, download = a large file, upload = a URL that accepts POST.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(base, { base = it }, Modifier.weight(1f), label = { Text("LibreSpeed base URL") }, singleLine = true, keyboardOptions = url)
            }
            OutlinedButton(
                onClick = {
                    SpeedTestConfig.libreSpeed(base).let { ping = it.pingUrl; download = it.downloadUrl; upload = it.uploadUrl }
                },
                enabled = base.startsWith("http://") || base.startsWith("https://"),
            ) { Text("Fill LibreSpeed URLs") }
            OutlinedTextField(ping, { ping = it }, Modifier.fillMaxWidth(), label = { Text("Ping URL") }, singleLine = true, keyboardOptions = url)
            OutlinedTextField(download, { download = it }, Modifier.fillMaxWidth(), label = { Text("Download URL") }, singleLine = true, keyboardOptions = url)
            OutlinedTextField(upload, { upload = it }, Modifier.fillMaxWidth(), label = { Text("Upload URL (POST)") }, singleLine = true, keyboardOptions = url)
            val blocked = remember(ping, download, upload) { cleartextBlocked(listOf(ping, download, upload)) }
            if (blocked.isNotEmpty()) {
                Text(
                    "Plain HTTP to ${blocked.joinToString()} is not allowed in this build, so the test would fail. Use " +
                        "https://, or add the host to netlogger.cleartextHosts in local.properties and rebuild.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { onSave(ping, download, upload) }, enabled = changed) { Text("Save") }
            Text(
                if (saved.isComplete) "Saved. Each test: ping, then download and upload for 10 s each, 4 connections, " +
                    "at most 200 MB down / 50 MB up."
                else "No server saved: the speed test is off.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PublicTargetCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Secondary target: Cloudflare (public)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onChange)
            }
            Text(
                "Adds a second button to the speed test card that runs against ${SpeedTestConfig.CLOUDFLARE}. Not your " +
                    "server and not the default: the result depends on Cloudflare's nearest edge, and many large tests in " +
                    "a row are rate-limited (HTTP 429). Each result stores the URL it used.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
