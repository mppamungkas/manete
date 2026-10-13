package id.ac.pnp.netlogger.feature.logger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.ac.pnp.netlogger.core.ui.component.PlaceholderScreen

@Composable
internal fun LiveScreen(
    loggingPermissionsMissing: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (loggingPermissionsMissing) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Logging needs precise location and phone state permissions. " +
                            "Existing sessions and exports still work without them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onRequestPermissions) { Text("Review permissions") }
                }
            }
        }
        PlaceholderScreen(
            title = "Live",
            description = "Serving and neighbor cell values will appear here (milestone 2).",
        )
    }
}
