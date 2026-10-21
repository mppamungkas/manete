package id.my.pamslab.netlogger.feature.logger.navigation

import androidx.compose.runtime.Composable
import id.my.pamslab.netlogger.feature.logger.CaptureRowSlot
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import id.my.pamslab.netlogger.feature.logger.LiveScreen
import id.my.pamslab.netlogger.feature.logger.plan.LivePlanScreen
import kotlinx.serialization.Serializable

@Serializable
data object LiveRoute

/** Live › Plan: the floor plan of the running session (decision 006). */
@Serializable
data object LivePlanRoute

fun NavController.navigateToLive(navOptions: NavOptions? = null) = navigate(LiveRoute, navOptions)

/** [captureRow]: the traffic capture row from feature:capture, passed in by the app (features don't depend on each other). */
fun NavGraphBuilder.liveScreen(
    loggingPermissionsMissing: () -> Boolean,
    onRequestPermissions: () -> Unit,
    captureRow: CaptureRowSlot = {},
    /** Opens a problem report in Settings (decision 005), passed in by the app. */
    onOpenProblem: (String) -> Unit = {},
    onOpenPlan: () -> Unit = {},
    onClosePlan: () -> Unit = {},
) {
    composable<LiveRoute> {
        LiveScreen(
            loggingPermissionsMissing = loggingPermissionsMissing(),
            onRequestPermissions = onRequestPermissions,
            captureRow = captureRow,
            onOpenProblem = onOpenProblem,
            onOpenPlan = onOpenPlan,
        )
    }
    composable<LivePlanRoute> { LivePlanScreen(onBack = onClosePlan) }
}
