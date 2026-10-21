package id.my.pamslab.netlogger.feature.indoor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.data.PlanImportError
import java.util.Locale

/** Settings › Floor plans (decision 006, step 2): import, list, rename, scale, delete. */
@Composable
internal fun FloorPlansScreen(onBack: () -> Unit, onScale: (Long) -> Unit, viewModel: FloorPlansViewModel = hiltViewModel()) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val import by viewModel.import.collectAsStateWithLifecycle()
    val deleteRefused by viewModel.deleteRefused.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.onPicked(it) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.plans_back)) }
        Text(stringResource(R.string.plans_title), style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plans_what_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.plans_what_info), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pick.launch(arrayOf("image/*", "application/pdf")) }, enabled = import == ImportState.Idle) {
                    Text(stringResource(R.string.plans_import))
                }
            }
        }
        val list = plans
        when {
            list == null -> CircularProgressIndicator()
            list.isEmpty() -> Text(stringResource(R.string.plans_none), style = MaterialTheme.typography.bodyMedium)
            else -> list.forEach { PlanCard(it, onScale = { onScale(it.plan.id) }, onRename = viewModel::rename, onDelete = viewModel::delete) }
        }
    }

    when (val state = import) {
        ImportState.Idle -> Unit
        ImportState.Inspecting, ImportState.Importing -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.plans_importing))
                }
            },
        )
        is ImportState.Confirm -> ConfirmImportDialog(state, onImport = viewModel::confirmImport, onCancel = viewModel::dismissImport)
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissImport,
            confirmButton = { TextButton(onClick = viewModel::dismissImport) { Text(stringResource(R.string.plans_ok)) } },
            title = { Text(stringResource(R.string.plans_import_failed)) },
            text = { Text(stringResource(state.reason.messageRes())) },
        )
    }
    deleteRefused?.let { name ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteRefused,
            confirmButton = { TextButton(onClick = viewModel::dismissDeleteRefused) { Text(stringResource(R.string.plans_ok)) } },
            text = { Text(stringResource(R.string.plans_delete_refused, name)) },
        )
    }
}

@Composable
private fun PlanCard(
    summary: FloorPlanSummary,
    onScale: () -> Unit,
    onRename: (Long, String, String) -> Unit,
    onDelete: (FloorPlanSummary) -> Unit,
) {
    val plan = summary.plan
    var editing by rememberSaveable(plan.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(plan.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumbnail(summary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(plan.name, style = MaterialTheme.typography.titleMedium)
                    plan.floor?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        stringResource(R.string.plans_size, plan.widthPx, plan.heightPx) + " · " + scaleText(plan.metresPerPx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (summary.sessions == 0) stringResource(R.string.plans_unused)
                        else pluralStringResource(R.plurals.plans_used, summary.sessions, summary.sessions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.plans_edit)) }
                OutlinedButton(onClick = onScale) { Text(stringResource(R.string.plans_scale)) }
                OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.plans_delete)) }
            }
        }
    }
    if (editing) {
        NameDialog(
            title = stringResource(R.string.plans_edit_title),
            initialName = plan.name,
            initialFloor = plan.floor.orEmpty(),
            confirm = stringResource(R.string.plans_save),
            onConfirm = { name, floor -> onRename(plan.id, name, floor); editing = false },
            onCancel = { editing = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.plans_delete_title, plan.name)) },
            text = { Text(stringResource(R.string.plans_delete_info)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(summary) }) { Text(stringResource(R.string.plans_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.plans_cancel)) } },
        )
    }
}

@Composable
private fun Thumbnail(summary: FloorPlanSummary) {
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        when (val state = rememberPlanBitmap(summary.image, maxSide = THUMBNAIL_MAX_SIDE)) {
            PlanBitmapState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
            PlanBitmapState.Failed -> Text("?", color = MaterialTheme.colorScheme.error)
            is PlanBitmapState.Ready -> Image(state.bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun scaleText(metresPerPx: Double?): String =
    if (metresPerPx == null) stringResource(R.string.plans_no_scale)
    else stringResource(R.string.plans_scale_value, String.format(Locale.ROOT, "%.1f", 1 / metresPerPx))

@Composable
private fun ConfirmImportDialog(state: ImportState.Confirm, onImport: (String, String, Int) -> Unit, onCancel: () -> Unit) {
    var page by rememberSaveable(state.uri) { mutableStateOf("1") }
    val pages = state.pdfPages
    val pageNumber = page.toIntOrNull()?.takeIf { pages == null || it in 1..pages }
    NameDialog(
        title = stringResource(R.string.plans_import_title),
        initialName = state.name,
        initialFloor = "",
        confirm = stringResource(R.string.plans_import_confirm),
        confirmEnabled = pages == null || pageNumber != null,
        onConfirm = { name, floor -> onImport(name, floor, pageNumber ?: 1) },
        onCancel = onCancel,
    ) {
        if (pages != null && pages > 1) {
            OutlinedTextField(
                page, { page = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.plans_pdf_page, pages)) },
                isError = pageNumber == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    initialFloor: String,
    confirm: String,
    onConfirm: (String, String) -> Unit,
    onCancel: () -> Unit,
    confirmEnabled: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var floor by rememberSaveable { mutableStateOf(initialFloor) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.plans_name)) }, singleLine = true)
                OutlinedTextField(
                    floor, { floor = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.plans_floor)) },
                    supportingText = { Text(stringResource(R.string.plans_floor_info)) },
                    singleLine = true,
                )
                extra()
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, floor) }, enabled = confirmEnabled) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.plans_cancel)) } },
    )
}

private fun PlanImportError.messageRes() = when (this) {
    PlanImportError.UNSUPPORTED -> R.string.plans_error_unsupported
    PlanImportError.UNREADABLE -> R.string.plans_error_unreadable
    PlanImportError.PDF_PROTECTED -> R.string.plans_error_protected
    PlanImportError.TOO_LARGE -> R.string.plans_error_too_large
    PlanImportError.NOT_SAVED -> R.string.plans_error_not_saved
}

private const val THUMBNAIL_MAX_SIDE = 320
