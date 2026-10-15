package id.my.pamslab.netlogger.feature.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.data.CaptureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CaptureSettingsViewModel @Inject constructor(
    private val settings: CaptureSettings,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val allApps: StateFlow<Boolean> = settings.allApps
    val selectedApps: StateFlow<List<String>> = settings.selectedApps
    val nativeMtu: StateFlow<Int> = settings.nativeMtu

    /** Launcher apps; null while loading. */
    private val _installed = MutableStateFlow<List<InstalledApp>?>(null)
    internal val installed: StateFlow<List<InstalledApp>?> = _installed.asStateFlow()

    fun setAllApps(all: Boolean) = settings.setAllApps(all)

    fun setNativeMtu(mtu: Int) = settings.setNativeMtu(mtu)

    fun toggle(packageName: String) {
        val now = settings.selectedApps.value
        settings.setSelectedApps(if (packageName in now) now - packageName else now + packageName)
    }

    internal fun loadApps(iconPx: Int) {
        if (_installed.value != null) return
        viewModelScope.launch(Dispatchers.Default) { _installed.value = launcherApps(context, iconPx) }
    }
}

/** Traffic capture: which apps and the MTU (decision 003, step 3). Start/stop stays on the Live card. */
@Composable
internal fun CaptureSettingsScreen(onBack: () -> Unit, viewModel: CaptureSettingsViewModel = hiltViewModel()) {
    val allApps by viewModel.allApps.collectAsStateWithLifecycle()
    val selected by viewModel.selectedApps.collectAsStateWithLifecycle()
    val mtu by viewModel.nativeMtu.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val iconPx = with(LocalDensity.current) { ICON.roundToPx() }
    LaunchedEffect(iconPx) { viewModel.loadApps(iconPx) }

    SettingsPage(stringResource(R.string.settings_capture), onBack) {
        SettingCard(stringResource(R.string.capture_what_title), stringResource(R.string.capture_what_info)) {
            Bullet(stringResource(R.string.capture_what_vpn))
            Bullet(stringResource(R.string.capture_what_limits))
            Bullet(stringResource(R.string.capture_what_files))
            Bullet(stringResource(R.string.capture_what_size))
        }
        SettingCard(stringResource(R.string.capture_apps_title), stringResource(R.string.capture_apps_info)) {
            Choices(
                options = listOf(true, false),
                selected = allApps,
                label = { stringResource(if (it) R.string.capture_apps_all else R.string.capture_apps_specific) },
                description = { stringResource(if (it) R.string.capture_apps_all_info else R.string.capture_apps_specific_info) },
                onSelect = viewModel::setAllApps,
            )
            if (!allApps) AppPicker(installed, selected, viewModel::toggle)
        }
        SettingCard(stringResource(R.string.capture_mtu_title), stringResource(R.string.capture_mtu_info)) {
            Choices(
                options = CaptureSettings.MTUS,
                selected = mtu,
                label = {
                    if (it == CaptureSettings.MTU_FAST) stringResource(R.string.capture_mtu_fast)
                    else stringResource(R.string.option_recommended, stringResource(R.string.capture_mtu_realistic))
                },
                description = { stringResource(if (it == CaptureSettings.MTU_FAST) R.string.capture_mtu_fast_info else R.string.capture_mtu_realistic_info) },
                onSelect = viewModel::setNativeMtu,
            )
        }
    }
}

@Composable
private fun AppPicker(installed: List<InstalledApp>?, selected: List<String>, onToggle: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Text(
        if (selected.isEmpty()) stringResource(R.string.capture_apps_none_selected)
        else pluralStringResource(R.plurals.capture_apps_selected, selected.size, selected.size),
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (installed == null) {
        Text(stringResource(R.string.capture_apps_loading), style = MaterialTheme.typography.bodySmall)
        return
    }
    OutlinedTextField(
        query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(R.string.capture_apps_search)) },
    )
    // Selected when the picker opened: these stay on top, so ticking an app doesn't move the list.
    val pinned = remember { selected }
    val rows = remember(installed, selected, query) { pickerRows(installed, selected, pinned, query) }
    // A plain Column inside the page's scroll: a few hundred rows at most (launcher apps only).
    Column {
        rows.forEach { row -> AppRowItem(row, onToggle) }
    }
    Text(stringResource(R.string.capture_apps_hidden), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AppRowItem(row: AppRow, onToggle: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = row.selected, onValueChange = { onToggle(row.packageName) }, role = Role.Checkbox)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.selected, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
        Box(Modifier.size(ICON)) {
            row.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(ICON)) }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(row.label ?: stringResource(R.string.capture_apps_not_found), style = MaterialTheme.typography.bodyLarge)
            Text(row.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ICON = 36.dp
