package id.my.pamslab.netlogger.feature.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import id.my.pamslab.netlogger.core.diagnostics.Problem
import id.my.pamslab.netlogger.core.diagnostics.ProblemKind
import id.my.pamslab.netlogger.core.diagnostics.ProblemReports
import id.my.pamslab.netlogger.core.diagnostics.ProblemSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
internal class ProblemsViewModel @Inject constructor(val reports: ProblemReports) : ViewModel()

/** Settings › Problem reports: the kept reports, newest first, and "Describe a problem" (decision 005). */
@Composable
internal fun ProblemsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onDescribe: () -> Unit, viewModel: ProblemsViewModel = hiltViewModel()) {
    val reports by viewModel.reports.reports.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.problems_title), onBack) {
        Text(stringResource(R.string.problems_info), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onDescribe) { Text(stringResource(R.string.problems_describe)) }
        if (reports.isEmpty()) {
            Text(stringResource(R.string.problems_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        reports.forEach { p ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatTime(p.shownTimeMs), style = MaterialTheme.typography.titleSmall)
                    Text(p.title ?: kindLabel(p.kind), style = MaterialTheme.typography.bodyMedium)
                    if (!p.seen) Text(stringResource(R.string.problems_new), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** One report: its full text, and Share, GitHub issue, Delete. */
@Composable
internal fun ProblemScreen(id: String, onBack: () -> Unit, viewModel: ProblemsViewModel = hiltViewModel()) {
    val reports by viewModel.reports.reports.collectAsStateWithLifecycle()
    val problem = reports.firstOrNull { it.id == id }
    val text by produceState<String?>(null, problem?.id) {
        value = problem?.let { p -> withContext(Dispatchers.IO) { runCatching { viewModel.reports.report(p) }.getOrElse { "Report not readable: $it" } } }
    }
    LaunchedEffect(problem?.id) {
        if (problem != null && !problem.seen) withContext(Dispatchers.IO) { viewModel.reports.markSeen(problem.id) }
    }
    var attachRaw by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Android does not tell whether the report was sent in the other app: ask once on the way back.
    var handedOver by rememberSaveable { mutableStateOf(false) }
    var askSent by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && handedOver) {
                handedOver = false
                askSent = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SettingsPage(stringResource(R.string.problems_report_title), onBack, backLabel = stringResource(R.string.problems_back)) {
        if (problem == null) {
            Text(stringResource(R.string.problems_gone), style = MaterialTheme.typography.bodyMedium)
            return@SettingsPage
        }
        Text(problem.title ?: kindLabel(problem.kind), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.problems_check_before_sending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReportText(text)
        val rawTrace = viewModel.reports.file(problem.id, ProblemReports.TRACE).takeIf { problem.kind == ProblemKind.CRASH_NATIVE }
        if (rawTrace != null) {
            Row(Modifier.fillMaxWidth().clickable { attachRaw = !attachRaw }, verticalAlignment = Alignment.Top) {
                Checkbox(checked = attachRaw, onCheckedChange = { attachRaw = it })
                Column(Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.problems_attach_raw), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.problems_attach_raw_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val title = problem.title ?: kindLabel(problem.kind)
        SendButtons(title, text, rawTrace?.takeIf { attachRaw }, onHandedOver = { handedOver = true })
        OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.problems_delete)) }
    }
    if (askSent && problem != null) {
        AlertDialog(
            onDismissRequest = { askSent = false },
            title = { Text(stringResource(R.string.problems_sent_title)) },
            text = { Text(stringResource(R.string.problems_sent_info)) },
            confirmButton = {
                TextButton(onClick = {
                    askSent = false
                    viewModel.reports.delete(problem.id)
                    onBack()
                }) { Text(stringResource(R.string.problems_sent_delete)) }
            },
            dismissButton = { TextButton(onClick = { askSent = false }) { Text(stringResource(R.string.problems_sent_keep)) } },
        )
    }
    if (confirmDelete && problem != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.problems_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.reports.delete(problem.id)
                    onBack()
                }) { Text(stringResource(R.string.problems_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.problems_cancel)) } },
        )
    }
}

/** "Describe a problem": the user's note with NetLogger's last log lines, sent like a report; not kept. */
@Composable
internal fun DescribeProblemScreen(onBack: () -> Unit, viewModel: ProblemsViewModel = hiltViewModel()) {
    var note by rememberSaveable { mutableStateOf("") }
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPage(stringResource(R.string.problems_describe), onBack, backLabel = stringResource(R.string.problems_back)) {
        Text(stringResource(R.string.problems_describe_info), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = note,
            onValueChange = {
                note = it
                report = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.problems_describe_hint)) },
            minLines = 4,
        )
        val current = report
        if (current == null) {
            PreviewButton(note, viewModel) { report = it }
        } else {
            Text(stringResource(R.string.problems_check_before_sending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReportText(current)
            SendButtons(stringResource(R.string.problems_user_title), current, rawTrace = null, onHandedOver = {})
        }
    }
}

/** Builds the report (reads the log, so off the main thread) so the user sees it before sending. */
@Composable
private fun PreviewButton(note: String, viewModel: ProblemsViewModel, onReport: (String) -> Unit) {
    var building by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(building) {
        if (building) {
            onReport(withContext(Dispatchers.IO) { viewModel.reports.userReport(note) })
            building = false
        }
    }
    Button(onClick = { building = true }, enabled = note.isNotBlank() && !building) { Text(stringResource(R.string.problems_preview)) }
}

@Composable
private fun ReportText(text: String?) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text ?: "…",
                Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
}

/** Share (any app) and GitHub issue (public; warned first). Nothing is sent without the user's choice. */
@Composable
private fun SendButtons(title: String, report: String?, rawTrace: File?, onHandedOver: () -> Unit) {
    val context = LocalContext.current
    var confirmGithub by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            report?.let { if (start(context) { ProblemSharing.shareIntent(context, title, it, rawTrace) }) onHandedOver() }
        }, enabled = report != null) { Text(stringResource(R.string.problems_share)) }
        OutlinedButton(onClick = { confirmGithub = true }, enabled = report != null) { Text(stringResource(R.string.problems_github)) }
    }
    if (confirmGithub && report != null) {
        AlertDialog(
            onDismissRequest = { confirmGithub = false },
            title = { Text(stringResource(R.string.problems_github_title)) },
            text = { Text(stringResource(R.string.problems_github_info)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmGithub = false
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Manete report", report))
                    if (start(context) { ProblemSharing.githubIssueIntent(title, report) }) onHandedOver()
                }) { Text(stringResource(R.string.problems_github_continue)) }
            },
            dismissButton = { TextButton(onClick = { confirmGithub = false }) { Text(stringResource(R.string.problems_cancel)) } },
        )
    }
}

/** True if another app was opened with the report. */
private fun start(context: Context, intent: () -> android.content.Intent): Boolean = try {
    context.startActivity(intent())
    true
} catch (e: ActivityNotFoundException) {
    CaughtErrors.caught("no app to send the report", e)
    false
} catch (e: IllegalArgumentException) { // FileProvider path
    CaughtErrors.caught("report attachment not shared", e)
    false
}

@Composable
private fun kindLabel(kind: ProblemKind): String = stringResource(
    when (kind) {
        ProblemKind.CRASH -> R.string.problems_kind_crash
        ProblemKind.CRASH_NATIVE -> R.string.problems_kind_native
        ProblemKind.ANR -> R.string.problems_kind_anr
        ProblemKind.KILLED_WHILE_RECORDING -> R.string.problems_kind_killed
    },
)

private fun formatTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

/** For the Settings list entry. */
internal fun List<Problem>.unseen() = count { !it.seen }
