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

/** [licence]: a [Licence] name. */
@Serializable
data class LicenceRoute(val licence: String)

fun NavController.navigateToSettings(navOptions: NavOptions? = null) = navigate(SettingsGraph, navOptions)

fun NavGraphBuilder.settingsGraph(navController: NavController) {
    navigation<SettingsGraph>(startDestination = SettingsRoute) {
        composable<SettingsRoute> { SettingsScreen(onOpen = { navController.navigate(it) }) }
        composable<LoggingSettingsRoute> { LoggingSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<SpeedTestSettingsRoute> { SpeedTestSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<CaptureSettingsRoute> { CaptureSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<CapabilitiesRoute> { CapabilitiesScreen(onBack = { navController.popBackStack() }) }
        composable<HelpRoute> { HelpScreen(onBack = { navController.popBackStack() }) }
        composable<AboutRoute> {
            AboutScreen(onBack = { navController.popBackStack() }, onLicence = { navController.navigate(LicenceRoute(it.name)) })
        }
        composable<LicenceRoute> { entry ->
            val name = entry.toRoute<LicenceRoute>().licence
            LicenceScreen(Licence.entries.firstOrNull { it.name == name }, onBack = { navController.popBackStack() })
        }
    }
}
