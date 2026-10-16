package id.my.pamslab.netlogger.feature.logger.navigation

import androidx.compose.runtime.Composable
import id.my.pamslab.netlogger.feature.logger.CaptureRowSlot
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import id.my.pamslab.netlogger.feature.logger.LiveScreen
import kotlinx.serialization.Serializable

@Serializable
data object LiveRoute

fun NavController.navigateToLive(navOptions: NavOptions? = null) = navigate(LiveRoute, navOptions)

/** [captureRow]: the traffic capture row from feature:capture, passed in by the app (features don't depend on each other). */
fun NavGraphBuilder.liveScreen(
    loggingPermissionsMissing: () -> Boolean,
    onRequestPermissions: () -> Unit,
    captureRow: CaptureRowSlot = {},
) {
    composable<LiveRoute> {
        LiveScreen(
            loggingPermissionsMissing = loggingPermissionsMissing(),
            onRequestPermissions = onRequestPermissions,
            captureRow = captureRow,
        )
    }
}
