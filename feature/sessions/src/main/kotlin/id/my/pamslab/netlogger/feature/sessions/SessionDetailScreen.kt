package id.my.pamslab.netlogger.feature.sessions

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.export.SizeEstimate
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.core.ui.component.PlaceholderScreen
import id.my.pamslab.netlogger.feature.sessions.charts.SessionCharts
import kotlinx.coroutines.launch

@Composable
internal fun SessionDetailScreen(
    onBack: () -> Unit,
    onShowOnMap: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val size by viewModel.size.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val charts by viewModel.charts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(viewModel::saveTo)
    }
    // The export runs outside this screen; a finished Share opens the chooser here, also after coming back.
    val shareIntent = export?.shareIntent
    LaunchedEffect(shareIntent) {
        if (shareIntent != null) {
            viewModel.shareStarted()
            try {
                context.startActivity(shareIntent)
            } catch (e: ActivityNotFoundException) {
                Log.w("NetLogger", "no app to share with", e)
            }
        }
    }
    // A finished export's result is shown until the screen is left after it was seen.
    DisposableEffect(viewModel) { onDispose { viewModel.dismissExport() } }
    val s = session ?: return PlaceholderScreen("Session", "Loading…", modifier)
    DetailContent(
        s, size, message, exporting, export, viewModel::save, onDelete = { viewModel.delete(onBack) },
        onSaveZip = { scope.launch { viewModel.exportFileName()?.let(saveLauncher::launch) } },
        onShareZip = viewModel::share,
        onShowOnMap = { onShowOnMap(s.id) },
        onBack = onBack, modifier = modifier,
        charts = { ChartsSection(charts, recording = s.endedAtMs == null, onReload = viewModel::reloadCharts) },
    )
}

@Composable
private fun DetailContent(
    s: SessionSummary,
    size: SizeEstimate?,
    message: String?,
    exporting: Boolean,
    export: ExportJob?,
    onSave: (String, String, Scenario?) -> Unit,
    onDelete: () -> Unit,
    onSaveZip: () -> Unit,
    onShareZip: () -> Unit,
    onShowOnMap: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    charts: @Composable () -> Unit = {},
) {
    var label by rememberSaveable(s.id) { mutableStateOf(s.label.orEmpty()) }
    var notes by rememberSaveable(s.id) { mutableStateOf(s.notes.orEmpty()) }
    var scenario by rememberSaveable(s.id) { mutableStateOf(s.scenario) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Sessions") }
        Text(s.label ?: formatTime(s.startedAtMs), style = MaterialTheme.typography.headlineSmall)
        Info("Started", formatTime(s.startedAtMs))
        Info("Ended", s.endedAtMs?.let(::formatTime) ?: "–")
        Info("Duration", duration(s))
        status(s)?.let { Info("Status", it) }
        Info("Device", s.deviceModel)
        Info("Operator", s.operatorName ?: "–")
        Info("Interval", "${s.intervalMs} ms")
        Info("Ticks / cells", "${s.measurements} / ${s.cells}")
        Info("Markers", s.markers.toString())
        Info("Warnings (distinct)", s.warnings.toString())
        size?.let { Info("Size", sizeLine(it)) }
        Info("UUID", s.uuid)
        charts()

        OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Label") }, singleLine = true)
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Scenario.entries.forEach { option ->
                FilterChip(
                    selected = option == scenario,
                    onClick = { scenario = if (scenario == option) null else option },
                    label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(label, notes, scenario) }) { Text("Save") }
            OutlinedButton(onClick = { confirmDelete = true }, enabled = s.endedAtMs != null) { Text("Delete") }
        }
        val canExport = s.endedAtMs != null && !exporting
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSaveZip, enabled = canExport) { Text("Save ZIP…") }
            OutlinedButton(onClick = onShareZip, enabled = canExport) { Text("Share ZIP") }
        }
        OutlinedButton(onClick = onShowOnMap) { Text("Show on map") }
        if (s.endedAtMs == null) Text("Export is available after the session is stopped.", style = MaterialTheme.typography.bodySmall)
        export?.let { ExportStatus(it) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("${s.measurements} ticks, ${s.markers} markers and all cells of this session are deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** The running export's progress, or its result. Leaving the screen does not stop it. */
@Composable
private fun ExportStatus(job: ExportJob) {
    if (job.running) {
        val p = job.progress
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { p.fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(
                (if (job.kind == ExportJob.Kind.SAVE) "Saving ZIP" else "Preparing ZIP") +
                    (p?.let { " · ${it.file} · ${(it.fraction * 100).toInt()} %" } ?: "…") +
                    " · you can leave this screen",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        Text(
            job.message.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (job.failed) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

@Composable
private fun ChartsSection(state: ChartState, recording: Boolean, onReload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Charts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (recording) TextButton(onClick = onReload) { Text("Reload") }
        }
        when (state) {
            ChartState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
            ChartState.Empty -> Text("No ticks in this session.", style = MaterialTheme.typography.bodySmall)
            is ChartState.Failed -> Text("Charts not loaded: ${state.message}", color = MaterialTheme.colorScheme.error)
            is ChartState.Ready -> SessionCharts(state.data, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
