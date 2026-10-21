package id.my.pamslab.netlogger.feature.indoor.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import id.my.pamslab.netlogger.feature.indoor.FloorPlansScreen
import id.my.pamslab.netlogger.feature.indoor.PlanScaleScreen
import kotlinx.serialization.Serializable

/** Settings › Floor plans (decision 006). */
@Serializable
data object FloorPlansRoute

/** Scale calibration of one plan. */
@Serializable
data class PlanScaleRoute(val planId: Long)

/**
 * The floor plan pages. :app adds them inside the Settings tab's graph (features don't depend on each other), so the
 * Settings tab stays selected and tapping it returns to the Settings list.
 */
fun NavGraphBuilder.floorPlanPages(navController: NavController) {
    composable<FloorPlansRoute> {
        FloorPlansScreen(
            onBack = { navController.popBackStack() },
            onScale = { navController.navigate(PlanScaleRoute(it)) { launchSingleTop = true } },
        )
    }
    composable<PlanScaleRoute> { PlanScaleScreen(onBack = { navController.popBackStack() }) }
}
