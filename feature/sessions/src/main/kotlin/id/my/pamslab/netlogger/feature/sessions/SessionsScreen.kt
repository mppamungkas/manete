package id.my.pamslab.netlogger.feature.sessions

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.model.session.EndReason
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.core.ui.component.PlaceholderScreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SessionsScreen(
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val list = sessions
    when {
        list == null -> PlaceholderScreen("Sessions", "Loading…", modifier)
        list.isEmpty() -> PlaceholderScreen("Sessions", "No sessions yet. Start logging on the Live tab.", modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.id }) { SessionRow(it, onClick = { onOpen(it.id) }) }
        }
    }
}

@Composable
private fun SessionRow(s: SessionSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.label ?: formatTime(s.startedAtMs), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    s.scenario?.name?.lowercase(),
                    s.operatorName,
                    s.deviceModel,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(duration(s), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("${s.measurements} ticks", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(if (s.markers == 1L) "1 marker" else "${s.markers} markers", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            status(s)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        }
    }
}

internal fun status(s: SessionSummary): String? = when (s.endReason) {
    null -> "recording or not closed yet"
    EndReason.RECOVERED_AFTER_KILL -> "closed after the app was killed (end = last tick)"
    EndReason.ERROR -> "stopped by an error"
    EndReason.USER_STOP -> null
}

internal fun formatTime(ms: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

internal fun duration(s: SessionSummary): String {
    val end = s.endedAtMs ?: return "open"
    val total = ((end - s.startedAtMs) / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(Locale.ROOT, total / 3600, total / 60 % 60, total % 60)
}
