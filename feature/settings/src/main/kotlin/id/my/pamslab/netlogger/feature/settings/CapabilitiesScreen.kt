package id.my.pamslab.netlogger.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.data.FieldCoverage
import id.my.pamslab.netlogger.core.data.RecordedCoverage
import id.my.pamslab.netlogger.core.device.DeviceContextSource
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import id.my.pamslab.netlogger.core.model.device.DeviceInfo
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.raw.RawSubscription
import id.my.pamslab.netlogger.core.telephony.RadioSampler
import id.my.pamslab.netlogger.core.telephony.TelephonySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

internal data class CapabilitiesState(
    val checking: Boolean = false,
    val checkedAtMs: Long? = null,
    val device: DeviceInfo? = null,
    val sim: RawSubscription? = null,
    val now: PhoneNow? = null,
    /** The last radio snapshot of the check (network type, operator). */
    val radio: RadioSnapshot? = null,
    val rows: List<CapRow> = emptyList(),
    /** Null while counting, or if counting failed ([coverageFailed]). */
    val coverage: FieldCoverage? = null,
    val coverageFailed: Boolean = false,
)

@HiltViewModel
class CapabilitiesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sampler: RadioSampler,
    private val telephony: TelephonySource,
    private val deviceSource: DeviceContextSource,
    private val recorded: RecordedCoverage,
) : ViewModel() {
    private val _state = MutableStateFlow(CapabilitiesState())
    internal val state: StateFlow<CapabilitiesState> = _state.asStateFlow()
    private var job: Job? = null

    /** A fresh check: [SAMPLES] radio snapshots 1 s apart (the first may lack callback values), plus the database counts. */
    fun refresh() {
        if (job?.isActive == true) return
        _state.value = _state.value.copy(checking = true)
        job = viewModelScope.launch(Dispatchers.Default) {
            val device = runCatching { deviceSource.deviceInfo() }.getOrNull()
            val sim = runCatching { telephony.activeDataSubscription() }.getOrNull()
            val now = readPhoneNow(context)
            val snapshots = try {
                withTimeoutOrNull(CHECK_TIMEOUT_MS) { sampler.samples(1_000).take(SAMPLES).toList() }.orEmpty()
            } catch (e: Exception) {
                CaughtErrors.caught("capabilities: radio check failed", e)
                emptyList()
            }
            _state.value = _state.value.copy(
                checking = false, checkedAtMs = System.currentTimeMillis(), device = device, sim = sim, now = now,
                radio = snapshots.lastOrNull(), rows = evaluateCapabilities(snapshots),
            )
            val coverage = try {
                withContext(Dispatchers.IO) { recorded.coverage() }
            } catch (e: Exception) {
                CaughtErrors.caught("capabilities: counting recorded sessions failed", e)
                null
            }
            _state.value = _state.value.copy(coverage = coverage, coverageFailed = coverage == null)
        }
    }

    /** Puts [capabilitiesReport] on the clipboard. */
    fun copyReport() {
        val text = capabilitiesReport(context, _state.value)
        try {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("NetLogger phone capabilities", text))
        } catch (e: Exception) {
            CaughtErrors.caught("capabilities: copy failed", e)
        }
    }

    private companion object {
        const val SAMPLES = 3
        const val CHECK_TIMEOUT_MS = 15_000L
    }
}

/** Settings › Phone capabilities: a live check of what this phone reports (decision 003, step 4). */
@Composable
internal fun CapabilitiesScreen(onBack: () -> Unit, viewModel: CapabilitiesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    SettingsPage(stringResource(R.string.settings_capabilities), onBack) {
        SettingCard(stringResource(R.string.cap_about_title), stringResource(R.string.cap_about_info)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refresh, enabled = !state.checking) { Text(stringResource(R.string.cap_refresh)) }
                OutlinedButton(onClick = viewModel::copyReport, enabled = !state.checking && state.checkedAtMs != null) {
                    Text(stringResource(R.string.cap_copy))
                }
            }
            val checkedAt = state.checkedAtMs
            Text(
                when {
                    state.checking -> stringResource(R.string.cap_checking)
                    checkedAt != null -> stringResource(R.string.cap_checked_at, time(checkedAt))
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.checkedAtMs == null) return@SettingsPage
        SettingCard(stringResource(R.string.cap_device_title), stringResource(R.string.cap_device_info)) {
            deviceLines(LocalContext.current, state).forEach { (label, value) -> InfoLine(label, value) }
        }
        SettingCard(stringResource(R.string.cap_now_title), stringResource(R.string.cap_now_info)) {
            nowLines(LocalContext.current, state).forEach { (label, value, ok) -> InfoLine(label, value, ok) }
        }
        SettingCard(stringResource(R.string.cap_reports_title), stringResource(R.string.cap_reports_info)) {
            val coverage = state.coverage
            Text(
                when {
                    coverage != null -> stringResource(R.string.cap_recorded_basis, coverage.sessions, coverage.ticks)
                    state.coverageFailed -> stringResource(R.string.cap_recorded_failed)
                    else -> stringResource(R.string.cap_recorded_counting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CapGroup.entries.forEach { group ->
                Text(stringResource(group.titleRes()), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                state.rows.filter { it.field.group == group }.forEach { row ->
                    CapRowItem(row, coverage?.let { recordedShare(row.field, it) }, coverage != null)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, ok: Boolean? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.45f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = when (ok) {
                false -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
private fun CapRowItem(row: CapRow, share: Share?, counted: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(row.field.labelRes()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(row.status.labelRes()) + (row.value?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = row.status.color(),
            )
        }
        Text(stringResource(row.field.infoRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (counted) {
            Text(
                when {
                    share != null -> stringResource(R.string.cap_recorded_share, percent(share), share.of)
                    row.field.isRecorded -> stringResource(R.string.cap_recorded_none)
                    else -> stringResource(R.string.cap_recorded_not_stored)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CapStatus.color(): Color = when (this) {
    CapStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
    CapStatus.LIMITED -> MaterialTheme.colorScheme.tertiary
    CapStatus.MISSING -> MaterialTheme.colorScheme.error
    CapStatus.NEEDS_LTE, CapStatus.NEEDS_NR -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun deviceLines(context: Context, s: CapabilitiesState): List<Pair<String, String>> {
    val unknown = context.getString(R.string.unknown)
    val d = s.device
    val sim = s.sim
    return listOf(
        context.getString(R.string.cap_device_phone) to (d?.let { "${it.manufacturer} ${it.model}" } ?: unknown),
        context.getString(R.string.cap_device_android) to (d?.let { "${it.androidVersion} (SDK ${it.sdkInt})" } ?: unknown),
        context.getString(R.string.cap_device_baseband) to (d?.basebandVersion ?: unknown),
        context.getString(R.string.cap_device_app) to (d?.appVersion ?: unknown),
        context.getString(R.string.cap_device_sim) to (
            sim?.let {
                val code = if (it.mcc != null && it.mnc != null) "${it.mcc}-${it.mnc}" else unknown
                context.getString(R.string.cap_device_sim_value, it.carrierName ?: unknown, code, it.simSlot?.let { slot -> (slot + 1).toString() } ?: unknown)
            } ?: context.getString(R.string.logging_sim_none)
            ),
    )
}

/** label, value, ok (false = blocks or weakens a test; null = neutral). */
private fun nowLines(context: Context, s: CapabilitiesState): List<Triple<String, String, Boolean?>> {
    fun str(@StringRes id: Int) = context.getString(id)
    val unknown = str(R.string.unknown)
    fun yesNo(v: Boolean?) = when (v) {
        true -> str(R.string.yes)
        false -> str(R.string.no)
        null -> unknown
    }
    val n = s.now
    val radio = s.radio
    return listOf(
        Triple(
            str(R.string.cap_now_network),
            // The 5G icon state only when there is one (override NONE says nothing).
            radio?.let { r -> r.networkType.name + (overrideName(r.nsaInputs.overrideNetworkType.takeIf { it != 0 })?.let { " · icon $it" } ?: "") } ?: unknown,
            null,
        ),
        Triple(str(R.string.cap_now_operator), radio?.let { r -> listOfNotNull(r.operatorName, r.operatorNumeric).joinToString(" · ") }.orEmpty().ifEmpty { unknown }, null),
        Triple(
            str(R.string.cap_now_mobile_ip),
            if (n?.mobileIpv4 == null) str(R.string.cap_now_no_mobile_data)
            else listOfNotNull("IPv4".takeIf { n.mobileIpv4 == true }, "IPv6".takeIf { n.mobileIpv6 == true }).joinToString(" + ").ifEmpty { str(R.string.cap_now_no_address) },
            n?.mobileIpv4 != null,
        ),
        Triple(str(R.string.cap_now_wifi), if (n?.wifiConnected == true) str(R.string.cap_now_wifi_on) else yesNo(n?.wifiConnected), n?.wifiConnected?.not()),
        Triple(str(R.string.cap_now_vpn), if (n?.vpnActive == true) str(R.string.cap_now_vpn_on) else yesNo(n?.vpnActive), null),
        Triple(str(R.string.cap_now_location), yesNo(n?.locationOn), n?.locationOn),
        Triple(str(R.string.cap_now_perm_location), yesNo(n?.locationPermission), n?.locationPermission),
        Triple(str(R.string.cap_now_perm_phone), yesNo(n?.phonePermission), n?.phonePermission),
        Triple(str(R.string.cap_now_perm_notifications), yesNo(n?.notificationsEnabled), n?.notificationsEnabled),
        Triple(str(R.string.cap_now_battery), if (n?.batteryExempt == true) str(R.string.cap_now_battery_exempt) else if (n?.batteryExempt == false) str(R.string.cap_now_battery_optimised) else unknown, n?.batteryExempt),
    )
}

/** The page as plain text (Markdown table), e.g. for docs/device-quirks.md or a teacher. */
internal fun capabilitiesReport(context: Context, s: CapabilitiesState): String = buildString {
    appendLine("NetLogger phone capabilities · ${s.checkedAtMs?.let { time(it, withDate = true) } ?: ""}")
    appendLine()
    deviceLines(context, s).forEach { (label, value) -> appendLine("- $label: $value") }
    appendLine()
    appendLine(context.getString(R.string.cap_now_title))
    nowLines(context, s).forEach { (label, value, _) -> appendLine("- $label: $value") }
    appendLine()
    val c = s.coverage
    appendLine(context.getString(R.string.cap_reports_title))
    if (c != null) appendLine(context.getString(R.string.cap_recorded_basis, c.sessions, c.ticks))
    appendLine()
    appendLine("| ${context.getString(R.string.cap_col_field)} | ${context.getString(R.string.cap_col_now)} | ${context.getString(R.string.cap_col_recorded)} |")
    appendLine("|---|---|---|")
    s.rows.forEach { row ->
        val now = context.getString(row.status.labelRes()) + (row.value?.let { " · $it" } ?: "")
        val share = c?.let { recordedShare(row.field, it) }?.let { context.getString(R.string.cap_report_share, percent(it), it.of) } ?: "–"
        // PCI and Band exist for both RATs: the table has no group headings, so name the RAT.
        val rat = when (row.field.group) {
            CapGroup.LTE -> "LTE "
            CapGroup.NR -> "NR "
            CapGroup.OTHER -> ""
        }
        appendLine("| $rat${context.getString(row.field.labelRes())} | $now | $share |")
    }
}

/** Whole percent, but never "100" unless every tick had it, nor "0" unless none did. */
internal fun percent(share: Share): String {
    if (share.of <= 0) return "–"
    val p = share.count * 100.0 / share.of
    val rounded = when {
        share.count == share.of -> 100
        share.count == 0L -> 0
        else -> p.toInt().coerceIn(1, 99)
    }
    return "$rounded %"
}

private fun time(ms: Long, withDate: Boolean = false): String =
    DateTimeFormatter.ofPattern(if (withDate) "yyyy-MM-dd HH:mm:ss xxx" else "HH:mm:ss", Locale.ROOT)
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@StringRes
private fun CapGroup.titleRes(): Int = when (this) {
    CapGroup.LTE -> R.string.cap_group_lte
    CapGroup.NR -> R.string.cap_group_nr
    CapGroup.OTHER -> R.string.cap_group_other
}

@StringRes
private fun CapStatus.labelRes(): Int = when (this) {
    CapStatus.AVAILABLE -> R.string.cap_status_available
    CapStatus.LIMITED -> R.string.cap_status_limited
    CapStatus.MISSING -> R.string.cap_status_missing
    CapStatus.NEEDS_LTE -> R.string.cap_status_needs_lte
    CapStatus.NEEDS_NR -> R.string.cap_status_needs_nr
}

@StringRes
private fun CapField.labelRes(): Int = when (this) {
    CapField.LTE_RSRP -> R.string.cap_lte_rsrp
    CapField.LTE_RSRQ -> R.string.cap_lte_rsrq
    CapField.LTE_SINR -> R.string.cap_lte_sinr
    CapField.LTE_RSSI -> R.string.cap_lte_rssi
    CapField.LTE_CQI -> R.string.cap_lte_cqi
    CapField.LTE_TA -> R.string.cap_lte_ta
    CapField.LTE_PCI -> R.string.cap_lte_pci
    CapField.LTE_EARFCN -> R.string.cap_lte_earfcn
    CapField.LTE_BAND -> R.string.cap_lte_band
    CapField.LTE_CELL_ID -> R.string.cap_lte_cell_id
    CapField.NR_CELL_INFO -> R.string.cap_nr_cell_info
    CapField.NR_SIGNAL_STRENGTH -> R.string.cap_nr_signal_strength
    CapField.NR_SS_RSRP -> R.string.cap_nr_rsrp
    CapField.NR_SS_RSRQ -> R.string.cap_nr_rsrq
    CapField.NR_SS_SINR -> R.string.cap_nr_sinr
    CapField.NR_CSI_RSRP -> R.string.cap_nr_csi
    CapField.NR_PCI -> R.string.cap_nr_pci
    CapField.NR_ARFCN -> R.string.cap_nr_arfcn
    CapField.NR_BAND -> R.string.cap_nr_band
    CapField.CELL_BANDWIDTHS -> R.string.cap_bandwidths
    CapField.NEIGHBOURS -> R.string.cap_neighbours
    CapField.FRESH_CELL_INFO -> R.string.cap_fresh
}

@StringRes
private fun CapField.infoRes(): Int = when (this) {
    CapField.LTE_RSRP -> R.string.cap_lte_rsrp_info
    CapField.LTE_RSRQ -> R.string.cap_lte_rsrq_info
    CapField.LTE_SINR -> R.string.cap_lte_sinr_info
    CapField.LTE_RSSI -> R.string.cap_lte_rssi_info
    CapField.LTE_CQI -> R.string.cap_lte_cqi_info
    CapField.LTE_TA -> R.string.cap_lte_ta_info
    CapField.LTE_PCI -> R.string.cap_lte_pci_info
    CapField.LTE_EARFCN -> R.string.cap_lte_earfcn_info
    CapField.LTE_BAND -> R.string.cap_band_info
    CapField.LTE_CELL_ID -> R.string.cap_lte_cell_id_info
    CapField.NR_CELL_INFO -> R.string.cap_nr_cell_info_info
    CapField.NR_SIGNAL_STRENGTH -> R.string.cap_nr_signal_strength_info
    CapField.NR_SS_RSRP -> R.string.cap_nr_rsrp_info
    CapField.NR_SS_RSRQ -> R.string.cap_nr_rsrq_info
    CapField.NR_SS_SINR -> R.string.cap_nr_sinr_info
    CapField.NR_CSI_RSRP -> R.string.cap_nr_csi_info
    CapField.NR_PCI -> R.string.cap_nr_pci_info
    CapField.NR_ARFCN -> R.string.cap_nr_arfcn_info
    CapField.NR_BAND -> R.string.cap_band_info
    CapField.CELL_BANDWIDTHS -> R.string.cap_bandwidths_info
    CapField.NEIGHBOURS -> R.string.cap_neighbours_info
    CapField.FRESH_CELL_INFO -> R.string.cap_fresh_info
}
