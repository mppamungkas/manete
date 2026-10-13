package id.my.pamslab.netlogger.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.my.pamslab.netlogger.core.ui.component.PlaceholderScreen

@Composable
internal fun SettingsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Settings",
        description = "Interval, SIM selection and export options.",
        modifier = modifier,
    )
}
