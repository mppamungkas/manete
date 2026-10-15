package id.my.pamslab.netlogger.feature.logger.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import id.my.pamslab.netlogger.feature.logger.LiveScreen
import kotlinx.serialization.Serializable

@Serializable
data object LiveRoute

fun NavController.navigateToLive(navOptions: NavOptions? = null) = navigate(LiveRoute, navOptions)

/** [captureCard]: the traffic capture card from feature:capture, passed in by the app (features don't depend on each other). */
fun NavGraphBuilder.liveScreen(
    loggingPermissionsMissing: () -> Boolean,
    onRequestPermissions: () -> Unit,
    captureCard: @Composable () -> Unit = {},
) {
    composable<LiveRoute> {
        LiveScreen(
            loggingPermissionsMissing = loggingPermissionsMissing(),
            onRequestPermissions = onRequestPermissions,
            captureCard = captureCard,
        )
    }
}
