package id.my.pamslab.netlogger.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.my.pamslab.netlogger.core.ui.component.PlaceholderScreen

@Composable
internal fun MapScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Map",
        description = "Session track colored by metric (milestone 6).",
        modifier = modifier,
    )
}
