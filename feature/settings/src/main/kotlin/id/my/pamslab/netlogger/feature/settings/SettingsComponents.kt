package id.my.pamslab.netlogger.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import id.my.pamslab.netlogger.core.model.session.Scenario

/** A Settings page: back link, title, scrolling content. */
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

/** A row of the Settings list: name and current value; opens its page. */
@Composable
internal fun SettingsEntry(title: String, summary: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A titled card with an explanation, e.g. one setting with its choices. */
@Composable
internal fun SettingCard(title: String, info: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(info, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

/** Single-choice options, each with a one-line description. */
@Composable
internal fun <T> Choices(options: List<T>, selected: T, label: @Composable (T) -> String, description: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = option == selected, onClick = null, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
                Column {
                    Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    Text(description(option), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@StringRes
internal fun Scenario.labelRes(): Int = when (this) {
    Scenario.DRIVE -> R.string.scenario_drive
    Scenario.WALK -> R.string.scenario_walk
    Scenario.STATIC -> R.string.scenario_static
    Scenario.INDOOR -> R.string.scenario_indoor
}

@StringRes
internal fun Scenario.infoRes(): Int = when (this) {
    Scenario.DRIVE -> R.string.scenario_drive_info
    Scenario.WALK -> R.string.scenario_walk_info
    Scenario.STATIC -> R.string.scenario_static_info
    Scenario.INDOOR -> R.string.scenario_indoor_info
}
