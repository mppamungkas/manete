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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig

@Composable
internal fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by viewModel.speedTest.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        SpeedTestServerCard(saved, viewModel::save)
        Text(
            "Interval, SIM selection and export options will follow.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SpeedTestServerCard(saved: SpeedTestConfig, onSave: (String, String, String) -> Unit) {
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
