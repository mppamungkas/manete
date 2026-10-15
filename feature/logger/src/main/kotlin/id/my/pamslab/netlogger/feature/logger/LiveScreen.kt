package id.my.pamslab.netlogger.feature.logger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import id.my.pamslab.netlogger.core.ui.component.PlaceholderScreen
import id.my.pamslab.netlogger.feature.logger.service.LoggingService
import id.my.pamslab.netlogger.feature.logger.service.LoggingState
import id.my.pamslab.netlogger.feature.logger.service.SpeedTestState
import java.util.Locale

/** Values older than this are grayed out. */
private const val STALE_AGE_MS = 5_000L

@Composable
internal fun LiveScreen(
    loggingPermissionsMissing: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    captureCard: @Composable () -> Unit = {},
) {
    if (loggingPermissionsMissing) {
        Column(modifier.fillMaxSize()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Logging needs precise location and phone state permissions. " +
                            "Existing sessions and exports still work without them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onRequestPermissions) { Text("Review permissions") }
                }
            }
            PlaceholderScreen(title = "Live", description = "Radio values appear here once permissions are granted.")
        }
    } else {
        // Only created (and sampling) when permissions are present.
        LiveContent(hiltViewModel(), captureCard, modifier)
    }
}

@Composable
private fun LiveContent(viewModel: LiveViewModel, captureCard: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LiveUiState.Waiting -> PlaceholderScreen("Live", "Waiting for the first radio sample…", modifier)
        is LiveUiState.Failed -> PlaceholderScreen("Live", "Sampling stopped: ${s.message}", modifier)
        is LiveUiState.Ready -> {
            val scenario by viewModel.scenario.collectAsStateWithLifecycle()
            SnapshotView(s, scenario, viewModel::selectScenario, captureCard, modifier)
        }
    }
}

@Composable
private fun SnapshotView(
    ui: LiveUiState.Ready,
    scenario: Scenario,
    onScenario: (Scenario) -> Unit,
    captureCard: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = ui.snapshot
    val serving = snapshot.cells.filter { it.servingInferred }
    // Stale SignalStrength rows have no identity and are not neighbors; they show up as a warning.
    val neighbors = snapshot.cells.filter { !it.servingInferred && it.source == CellSource.CELL_INFO }.sortedByDescending { it.rsrpDbm ?: it.rssiDbm ?: Int.MIN_VALUE }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { LoggingCard(ui.logging, scenario, onScenario) }
        (ui.logging as? LoggingState.Running)?.let { item { SpeedTestCard(it.speedTest) } }
        item { captureCard() } // shows itself only while a session records or a capture is ending
        item { NetworkCard(snapshot) }
        item { LocationCard(ui.tick?.location ?: ui.location?.fix, ui.tick?.locationStatus ?: ui.location?.status, ui.locationAgeMs, ui.location?.error) }
        ui.tick?.device?.let { item { DeviceCard(it) } }
        items(serving) { ServingCellCard(it) }
        if (serving.isEmpty()) item { Text("No serving cell reported.", style = MaterialTheme.typography.bodyMedium) }
        item { NeighborTable(neighbors) }
        if (snapshot.warnings.isNotEmpty()) item { WarningsCard(snapshot) }
    }
}

@Composable
private fun LoggingCard(logging: LoggingState, scenario: Scenario, onScenario: (Scenario) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (logging) {
                is LoggingState.Running -> {
                    val st = logging.stats
                    Text("Logging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Field("Ticks", "${st.recorded} / ${st.expected} expected (%.1f %%) · ${st.missed} missed".format(Locale.ROOT, st.recordedPct))
                    Field("Interval", "${st.intervalMs} ms")
                    Field("Session", "#${logging.sessionId}")
                    Field("Markers", logging.markers.size.toString())
                    if (logging.writeErrors > 0) {
                        Text("${logging.writeErrors} ticks not stored, see logcat", color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { LoggingService.stop(context) }) { Text("Stop") }
                        OutlinedButton(onClick = { LoggingService.addMarker(context) }) { Text("Add marker") }
                    }
                }
                is LoggingState.Idle -> {
                    Text("Not logging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    logging.lastSession?.let { st ->
                        Field("Last session", "${st.recorded} / ${st.expected} ticks (%.1f %%)".format(Locale.ROOT, st.recordedPct))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Scenario.entries.forEach { s ->
                            FilterChip(
                                selected = s == scenario,
                                onClick = { onScenario(s) },
                                label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    Button(onClick = { LoggingService.start(context, scenario) }) { Text("Start logging") }
                }
            }
        }
    }
}

@Composable
private fun SpeedTestCard(test: SpeedTestState?) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Speed test", style = MaterialTheme.typography.titleSmall)
            when (test) {
                null -> Text("Ping, download and upload over mobile data; results are stored in this session.", style = MaterialTheme.typography.bodySmall)
                is SpeedTestState.Running -> {
                    val p = test.progress
                    Field(
                        p?.phase?.name?.lowercase() ?: "starting",
                        p?.mbps?.let { "%.1f Mbps".format(Locale.ROOT, it) } ?: p?.rttMs?.let { "%.0f ms".format(Locale.ROOT, it) } ?: "…",
                    )
                }
                is SpeedTestState.Refused -> Text(test.reason, color = MaterialTheme.colorScheme.error)
                is SpeedTestState.Done -> test.results.forEach { r ->
                    val value = when {
                        r.error != null -> r.error.orEmpty()
                        r.type == ThroughputType.PING ->
                            "%.0f ms avg · %.0f ms jitter · %.0f %% loss".format(Locale.ROOT, r.rttAvgMs ?: 0.0, r.jitterMs ?: 0.0, r.lossPct ?: 0.0)
                        else -> "%.1f Mbps · %.1f MB".format(Locale.ROOT, r.mbps ?: 0.0, (r.bytes ?: 0) / 1e6)
                    }
                    Field(r.type.name.lowercase(), value)
                }
            }
            OutlinedButton(onClick = { LoggingService.speedTest(context) }, enabled = test !is SpeedTestState.Running) {
                Text("Run speed test")
            }
        }
    }
}

@Composable
private fun LocationCard(fix: LocationFix?, status: LocationStatus?, ageMs: Long?, error: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("GPS · ${status.orDash()}", style = MaterialTheme.typography.titleSmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (fix != null) {
                val stale = (ageMs ?: 0) > STALE_AGE_MS
                Field("Lat / Lon", "%.6f / %.6f".format(Locale.ROOT, fix.latitude, fix.longitude), stale)
                Field("Accuracy", "${fix.accuracyM?.let { "%.1f".format(Locale.ROOT, it) }.orDash()} m", stale)
                Field("Speed / Bearing", "${fix.speedMps?.let { "%.1f".format(Locale.ROOT, it) }.orDash()} m/s / ${fix.bearingDeg?.let { "%.0f".format(Locale.ROOT, it) }.orDash()}°", stale)
                Field("Altitude", "${fix.altitudeM?.let { "%.0f".format(Locale.ROOT, it) }.orDash()} m", stale)
                Field("Age", "${ageMs.orDash()} ms", stale)
            }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceContext) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Device", style = MaterialTheme.typography.titleSmall)
            with(d.battery) {
                Field("Battery", "${levelPct.orDash()} % · ${if (isCharging == true) "charging" else if (isCharging == false) "discharging" else "–"}")
                Field("Current / Voltage", "${currentUa.orDash()} µA / ${voltageMv.orDash()} mV")
                Field("Battery temp", "${tempC.orDash()} °C")
            }
            Field("Thermal / Screen", "${d.thermalStatus.orDash()} / ${d.screenOn?.let { if (it) "on" else "off" }.orDash()}")
            with(d.traffic) {
                Field("Total rx / tx", "${rxBytesTotal.orDash()} / ${txBytesTotal.orDash()} B")
                Field("App rx / tx", "${rxBytesApp.orDash()} / ${txBytesApp.orDash()} B")
            }
        }
    }
}

@Composable
private fun NetworkCard(s: RadioSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.networkType.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${s.operatorName.orDash()} (${s.operatorNumeric.orDash()})", style = MaterialTheme.typography.titleMedium)
            Field("SIM", "subId ${s.subscriptionId.orDash()} · slot ${s.simSlot.orDash()}")
            if (s.dataSubscriptionId != s.subscriptionId) {
                Text(
                    "Data SIM is now subId ${s.dataSubscriptionId.orDash()}. This keeps logging subId ${s.subscriptionId.orDash()}; " +
                        "stop and start a new session to log the new SIM.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Field("Service / data", "${s.serviceState.orDash()} / ${s.dataState.orDash()} · roaming ${s.isRoaming.orDash()}")
            with(s.nsaInputs) {
                Field("NSA inputs", "lteServing=$lteServing nrCellInfo=$nrServingCellInfo nrSignal=$nrSignalStrength")
                Field("Display", "dataNetworkType=${dataNetworkType.orDash()} override=${overrideNetworkType.orDash()}")
            }
            Field("Bandwidths kHz", s.cellBandwidthsKhz.ifEmpty { null }?.joinToString().orDash())
            Field("Cell info", "${s.cellInfoOrigin} · ${s.cells.size} cells · ${s.warningCount} warnings" + if (s.parseErrors > 0) " · ${s.parseErrors} parse errors" else "")
            s.listenerError?.let { Text("Listener: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ServingCellCard(c: CellSample) {
    val stale = c.isStale()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${c.rat} serving · ${c.source} · ${c.connectionStatus.orDash()}",
                style = MaterialTheme.typography.titleSmall,
            )
            Field("PCI / ARFCN", "${c.pci.orDash()} / ${c.arfcn.orDash()}", stale)
            Field("Band", "${c.band.orDash()} (${c.bandSource.orDash()}) · ${c.bandwidthKhz.orDash()} kHz", stale)
            Field("Cell ID / TAC", "${c.cellId.orDash()} / ${c.tac.orDash()}", stale)
            Field("PLMN", "${c.mcc.orDash()}-${c.mnc.orDash()}", stale)
            Field("RSRP / RSRQ / SINR", "${c.rsrpDbm.orDash()} / ${c.rsrqDb.orDash()} / ${c.sinrDb.orDash()}", stale)
            Field("RSSI / CQI / TA", "${c.rssiDbm.orDash()} / ${c.cqi.orDash()} / ${c.timingAdvance.orDash()}", stale)
            if (c.csiRsrpDbm != null || c.csiRsrqDb != null || c.csiSinrDb != null) {
                Field("CSI RSRP / RSRQ / SINR", "${c.csiRsrpDbm.orDash()} / ${c.csiRsrqDb.orDash()} / ${c.csiSinrDb.orDash()}", stale)
            }
            if (c.rscpDbm != null || c.ecNoDb != null) Field("RSCP / Ec/No", "${c.rscpDbm.orDash()} / ${c.ecNoDb.orDash()}", stale)
            if (c.source == CellSource.SIGNAL_STRENGTH) {
                Field("Updated", c.cellInfoAgeMs?.let { "%.1f s ago".format(Locale.ROOT, it / 1000.0) }.orDash())
            } else {
                Field("Age", "${c.cellInfoAgeMs.orDash()} ms", stale)
            }
        }
    }
}

@Composable
private fun NeighborTable(neighbors: List<CellSample>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Neighbors (${neighbors.size})", style = MaterialTheme.typography.titleSmall)
            TableRow(listOf("RAT", "PCI", "ARFCN", "RSRP", "RSRQ", "Age"), header = true)
            neighbors.forEach { c ->
                TableRow(
                    listOf(c.rat.name, c.pci.orDash(), c.arfcn.orDash(), (c.rsrpDbm ?: c.rscpDbm ?: c.rssiDbm).orDash(), c.rsrqDb.orDash(), c.cellInfoAgeMs.orDash()),
                    stale = c.isStale(),
                )
            }
        }
    }
}

@Composable
private fun WarningsCard(s: RadioSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Parse warnings", style = MaterialTheme.typography.titleSmall)
            s.warnings.take(MAX_WARNINGS_SHOWN).forEach { w ->
                Text(
                    "${w.code} ${w.rat.orDash()} pci=${w.pci.orDash()} ${w.field.orDash()}=${w.rawValue.orDash()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, stale: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (stale) Color.Gray else Color.Unspecified,
        )
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, stale: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach {
            Text(
                it,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (header) FontWeight.Bold else null,
                color = if (stale) Color.Gray else Color.Unspecified,
            )
        }
    }
}

private const val MAX_WARNINGS_SHOWN = 10

// SignalStrength only fires on change, so a large age there means "unchanged", not "stale": never grayed out.
private fun CellSample.isStale() = source == CellSource.CELL_INFO && (cellInfoAgeMs ?: 0) > STALE_AGE_MS

private fun Any?.orDash(): String = this?.toString() ?: "–"
