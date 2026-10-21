package id.my.pamslab.netlogger.feature.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import id.my.pamslab.netlogger.feature.settings.AboutScreen
import id.my.pamslab.netlogger.feature.settings.CapabilitiesScreen
import id.my.pamslab.netlogger.feature.settings.CaptureSettingsScreen
import id.my.pamslab.netlogger.feature.settings.HelpScreen
import id.my.pamslab.netlogger.feature.settings.Licence
import id.my.pamslab.netlogger.feature.settings.LicenceScreen
import id.my.pamslab.netlogger.feature.settings.LoggingSettingsScreen
import id.my.pamslab.netlogger.feature.settings.DescribeProblemScreen
import id.my.pamslab.netlogger.feature.settings.ProblemScreen
import id.my.pamslab.netlogger.feature.settings.ProblemsScreen
import id.my.pamslab.netlogger.feature.settings.SettingsScreen
import id.my.pamslab.netlogger.feature.settings.SpeedTestSettingsScreen
import kotlinx.serialization.Serializable

/** The Settings tab: the list and its pages (decision 003). The bottom bar selects the tab by this graph. */
@Serializable
data object SettingsGraph

@Serializable
data object SettingsRoute

@Serializable
data object LoggingSettingsRoute

@Serializable
data object SpeedTestSettingsRoute

@Serializable
data object CaptureSettingsRoute

@Serializable
data object CapabilitiesRoute

@Serializable
data object HelpRoute

@Serializable
data object AboutRoute

@Serializable
data object ProblemsRoute

/** One kept problem report (decision 005); also opened from the Live card. */
@Serializable
data class ProblemRoute(val id: String)

@Serializable
data object DescribeProblemRoute

/** [licence]: a [Licence] name. */
@Serializable
data class LicenceRoute(val licence: String)

fun NavController.navigateToSettings(navOptions: NavOptions? = null) = navigate(SettingsGraph, navOptions)

/** The problem report [id] inside the Settings tab (from the Live card). */
fun NavController.navigateToProblem(id: String, navOptions: NavOptions? = null) = navigate(ProblemRoute(id), navOptions)

/**
 * [onOpenFloorPlans] and [otherPages]: pages of other features shown inside the Settings tab (the floor plans of
 * feature:indoor), passed in by :app because features don't depend on each other.
 */
fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    onOpenFloorPlans: () -> Unit = {},
    otherPages: NavGraphBuilder.() -> Unit = {},
) {
    navigation<SettingsGraph>(startDestination = SettingsRoute) {
        composable<SettingsRoute> { SettingsScreen(onOpen = { navController.navigate(it) }, onOpenFloorPlans = onOpenFloorPlans) }
        otherPages()
        composable<LoggingSettingsRoute> { LoggingSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<SpeedTestSettingsRoute> { SpeedTestSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<CaptureSettingsRoute> { CaptureSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<CapabilitiesRoute> { CapabilitiesScreen(onBack = { navController.popBackStack() }) }
        composable<HelpRoute> { HelpScreen(onBack = { navController.popBackStack() }) }
        composable<AboutRoute> {
            AboutScreen(onBack = { navController.popBackStack() }, onLicence = { navController.navigate(LicenceRoute(it.name)) })
        }
        composable<ProblemsRoute> {
            ProblemsScreen(
                onBack = { navController.popBackStack() },
                // Single top: a double tap opens one page, not two.
                onOpen = { navController.navigate(ProblemRoute(it)) { launchSingleTop = true } },
                onDescribe = { navController.navigate(DescribeProblemRoute) { launchSingleTop = true } },
            )
        }
        composable<ProblemRoute> { entry -> ProblemScreen(entry.toRoute<ProblemRoute>().id, onBack = { navController.popBackStack() }) }
        composable<DescribeProblemRoute> { DescribeProblemScreen(onBack = { navController.popBackStack() }) }
        composable<LicenceRoute> { entry ->
            val name = entry.toRoute<LicenceRoute>().licence
            LicenceScreen(Licence.entries.firstOrNull { it.name == name }, onBack = { navController.popBackStack() })
        }
    }
}
