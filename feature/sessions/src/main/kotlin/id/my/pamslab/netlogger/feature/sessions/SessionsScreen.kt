package id.my.pamslab.netlogger.feature.sessions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.my.pamslab.netlogger.core.ui.component.PlaceholderScreen

@Composable
internal fun SessionsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Sessions",
        description = "Recorded sessions, labels and export (milestones 4-5).",
        modifier = modifier,
    )
}
