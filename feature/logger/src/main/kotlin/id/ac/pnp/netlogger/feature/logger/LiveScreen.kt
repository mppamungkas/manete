package id.ac.pnp.netlogger.feature.logger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.ac.pnp.netlogger.core.model.radio.CellSample
import id.ac.pnp.netlogger.core.model.radio.RadioSnapshot
import id.ac.pnp.netlogger.core.ui.component.PlaceholderScreen

/** Values older than this are grayed out. */
private const val STALE_AGE_MS = 5_000L

@Composable
internal fun LiveScreen(
    loggingPermissionsMissing: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
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
        LiveContent(hiltViewModel(), modifier)
    }
}

@Composable
private fun LiveContent(viewModel: LiveViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LiveUiState.Waiting -> PlaceholderScreen("Live", "Waiting for the first radio sample…", modifier)
        is LiveUiState.Failed -> PlaceholderScreen("Live", "Sampling stopped: ${s.message}", modifier)
        is LiveUiState.Ready -> SnapshotView(s.snapshot, modifier)
    }
}

@Composable
private fun SnapshotView(snapshot: RadioSnapshot, modifier: Modifier = Modifier) {
    val serving = snapshot.cells.filter { it.isServing }
    val neighbors = snapshot.cells.filterNot { it.isServing }.sortedByDescending { it.rsrpDbm ?: it.rssiDbm ?: Int.MIN_VALUE }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { NetworkCard(snapshot) }
        items(serving) { ServingCellCard(it) }
        if (serving.isEmpty()) item { Text("No serving cell reported.", style = MaterialTheme.typography.bodyMedium) }
        item { NeighborTable(neighbors) }
        if (snapshot.warnings.isNotEmpty()) item { WarningsCard(snapshot) }
    }
}

@Composable
private fun NetworkCard(s: RadioSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.networkType.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${s.operatorName.orDash()} (${s.operatorNumeric.orDash()})", style = MaterialTheme.typography.titleMedium)
            Field("SIM", "subId ${s.subscriptionId.orDash()} · slot ${s.simSlot.orDash()}")
            Field("Service / data", "${s.serviceState.orDash()} / ${s.dataState.orDash()} · roaming ${s.isRoaming.orDash()}")
            with(s.nsaInputs) {
                Field("NSA inputs", "lteServing=$lteServing nrCellInfo=$nrServingCellInfo nrSignal=$nrSignalStrength")
                Field("Display", "dataNetworkType=${dataNetworkType.orDash()} override=${overrideNetworkType.orDash()}")
            }
            Field("Bandwidths kHz", s.cellBandwidthsKhz.ifEmpty { null }?.joinToString().orDash())
            Field("Cell info", "${s.cellInfoOrigin} · ${s.cells.size} cells · ${s.parseErrors} warnings")
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
            Field("Age", "${c.cellInfoAgeMs.orDash()} ms", stale)
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

private fun CellSample.isStale() = (cellInfoAgeMs ?: 0) > STALE_AGE_MS

private fun Any?.orDash(): String = this?.toString() ?: "–"
