package id.my.pamslab.netlogger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.model.speedtest.PeriodicSpeedTest
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestTarget
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import java.util.Locale

/** Speed test: how it works, the user's server, the public secondary target (decision 003, step 2). */
@Composable
internal fun SpeedTestSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by viewModel.speedTest.collectAsStateWithLifecycle()
    val periodic by viewModel.periodic.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_speed_test), onBack) {
        HowItWorksCard(saved)
        ServerCard(saved, viewModel::save, viewModel::cleartextBlocked)
        PeriodicCard(periodic, saved.isComplete, viewModel::savePeriodic)
        PublicTargetCard()
    }
}

/**
 * The speed test repeated during a session (2026-10-19): an interval, the server (private or Cloudflare, 2026-10-20),
 * and optional limits (phases, duration, caps) that lower the data use. Applies to sessions started after a change.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PeriodicCard(periodic: PeriodicSpeedTest, hasServer: Boolean, onSave: (PeriodicSpeedTest) -> Unit) {
    var showLimits by rememberSaveable { mutableStateOf(!periodic.isDefaultTest) }
    SettingCard(stringResource(R.string.speed_periodic_title), stringResource(R.string.speed_periodic_info)) {
        Choices(
            PeriodicSpeedTest.INTERVALS_MIN, periodic.intervalMin,
            label = { if (it == 0) stringResource(R.string.speed_periodic_off) else stringResource(R.string.speed_periodic_every, it) },
            description = {
                if (it == 0) stringResource(R.string.speed_periodic_off_info)
                else stringResource(R.string.speed_periodic_every_info, dataSize(periodic.copy(intervalMin = it).maxBytesPerHour))
            },
        ) { onSave(periodic.copy(intervalMin = it)) }
        if (periodic.intervalMin > 0) {
            Text(stringResource(R.string.speed_periodic_target), style = MaterialTheme.typography.labelLarge)
            Choices(
                listOf(SpeedTestTarget.PRIMARY, SpeedTestTarget.PUBLIC), periodic.target,
                label = { stringResource(if (it == SpeedTestTarget.PRIMARY) R.string.speed_periodic_target_private else R.string.speed_periodic_target_cloudflare) },
                description = {
                    stringResource(if (it == SpeedTestTarget.PRIMARY) R.string.speed_periodic_target_private_info else R.string.speed_periodic_target_cloudflare_info)
                },
            ) { onSave(periodic.copy(target = it)) }
        }
        if (periodic.intervalMin > 0 && periodic.target == SpeedTestTarget.PRIMARY && !hasServer) {
            Text(stringResource(R.string.speed_periodic_no_server), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (periodic.intervalMin > 0 && periodic.phases.isEmpty()) {
            Text(stringResource(R.string.speed_periodic_no_phase), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.speed_periodic_per_test, dataSize(periodic.maxBytesPerTest)),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { showLimits = !showLimits }) {
            Text(stringResource(if (showLimits) R.string.speed_periodic_limits_hide else R.string.speed_periodic_limits_show))
        }
        if (showLimits) {
            Text(stringResource(R.string.speed_periodic_limits_info), style = MaterialTheme.typography.bodySmall)
            ChipRow(stringResource(R.string.speed_periodic_phases)) {
                listOf(
                    ThroughputType.PING to R.string.speed_phase_ping,
                    ThroughputType.DOWNLOAD to R.string.speed_phase_download,
                    ThroughputType.UPLOAD to R.string.speed_phase_upload,
                ).forEach { (type, label) ->
                    val on = type in periodic.phases
                    FilterChip(
                        selected = on,
                        onClick = { onSave(periodic.copy(phases = if (on) periodic.phases - type else periodic.phases + type)) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            ChipRow(stringResource(R.string.speed_periodic_duration)) {
                PeriodicSpeedTest.DURATIONS_MS.forEach { ms ->
                    FilterChip(periodic.durationMs == ms, { onSave(periodic.copy(durationMs = ms)) }, { Text("${ms / 1000} s") })
                }
            }
            if (ThroughputType.DOWNLOAD in periodic.phases) {
                ChipRow(stringResource(R.string.speed_periodic_cap_down)) {
                    PeriodicSpeedTest.DOWNLOAD_CAPS.forEach { cap ->
                        FilterChip(periodic.maxDownloadBytes == cap, { onSave(periodic.copy(maxDownloadBytes = cap)) }, { Text(dataSize(cap)) })
                    }
                }
            }
            if (ThroughputType.UPLOAD in periodic.phases) {
                ChipRow(stringResource(R.string.speed_periodic_cap_up)) {
                    PeriodicSpeedTest.UPLOAD_CAPS.forEach { cap ->
                        FilterChip(periodic.maxUploadBytes == cap, { onSave(periodic.copy(maxUploadBytes = cap)) }, { Text(dataSize(cap)) })
                    }
                }
            }
            if (!periodic.isDefaultTest) {
                Text(stringResource(R.string.speed_periodic_not_comparable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onSave(PeriodicSpeedTest(intervalMin = periodic.intervalMin, target = periodic.target)) }) {
                    Text(stringResource(R.string.speed_periodic_reset))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, chips: @Composable () -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

/** "250 MB", "1.5 GB" (binary units, as the caps). */
private fun dataSize(bytes: Long): String =
    if (bytes >= 1024 * MB) "%.1f GB".format(Locale.ROOT, bytes.toDouble() / (1024 * MB)) else "${bytes / MB} MB"


@Composable
private fun HowItWorksCard(config: SpeedTestConfig) {
    val downMb = (config.maxDownloadBytes / MB).toInt()
    val upMb = (config.maxUploadBytes / MB).toInt()
    SettingCard(stringResource(R.string.speed_how_title), stringResource(R.string.speed_how_info)) {
        Bullet(pluralStringResource(R.plurals.speed_how_ping, config.pingCount, config.pingCount))
        val connections = pluralStringResource(R.plurals.speed_connections, config.streams, config.streams)
        Bullet(stringResource(R.string.speed_how_transfer, (config.durationMs / 1000).toInt(), connections, downMb, upMb))
        Bullet(stringResource(R.string.speed_how_data, downMb + upMb))
        Bullet(stringResource(R.string.speed_how_stored))
    }
}

@Composable
private fun ServerCard(
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

    SettingCard(stringResource(R.string.speed_server_title), stringResource(R.string.speed_server_info)) {
        UrlField(base, { base = it }, stringResource(R.string.speed_base_url), null)
        OutlinedButton(
            onClick = {
                SpeedTestConfig.libreSpeed(base).let { ping = it.pingUrl; download = it.downloadUrl; upload = it.uploadUrl }
            },
            enabled = base.trim().startsWith("http://") || base.trim().startsWith("https://"),
        ) { Text(stringResource(R.string.speed_fill)) }
        UrlField(ping, { ping = it }, stringResource(R.string.speed_ping_url), stringResource(R.string.speed_ping_url_info))
        UrlField(download, { download = it }, stringResource(R.string.speed_download_url), stringResource(R.string.speed_download_url_info))
        UrlField(upload, { upload = it }, stringResource(R.string.speed_upload_url), stringResource(R.string.speed_upload_url_info))
        val blocked = remember(ping, download, upload) { cleartextBlocked(listOf(ping, download, upload)) }
        if (blocked.isNotEmpty()) {
            Text(
                stringResource(R.string.speed_cleartext_blocked, blocked.joinToString()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { onSave(ping, download, upload) }, enabled = changed) { Text(stringResource(R.string.speed_save)) }
        Text(
            stringResource(if (saved.isComplete) R.string.speed_saved else R.string.speed_not_saved),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UrlField(value: String, onChange: (String) -> Unit, label: String, help: String?) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

@Composable
private fun PublicTargetCard() {
    SettingCard(stringResource(R.string.speed_public_title), stringResource(R.string.speed_public_info, SpeedTestConfig.CLOUDFLARE)) {}
}

private const val MB = 1024L * 1024
