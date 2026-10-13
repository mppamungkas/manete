package id.ac.pnp.netlogger.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.ac.pnp.netlogger.core.ui.component.PlaceholderScreen

@Composable
internal fun SettingsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Settings",
        description = "Interval, SIM selection and export options.",
        modifier = modifier,
    )
}
