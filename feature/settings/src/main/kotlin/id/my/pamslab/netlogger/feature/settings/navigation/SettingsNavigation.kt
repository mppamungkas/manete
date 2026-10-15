package id.my.pamslab.netlogger.feature.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import id.my.pamslab.netlogger.feature.settings.CapabilitiesScreen
import id.my.pamslab.netlogger.feature.settings.CaptureSettingsScreen
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

fun NavController.navigateToSettings(navOptions: NavOptions? = null) = navigate(SettingsGraph, navOptions)

fun NavGraphBuilder.settingsGraph(navController: NavController) {
    navigation<SettingsGraph>(startDestination = SettingsRoute) {
        composable<SettingsRoute> { SettingsScreen(onOpen = { navController.navigate(it) }) }
        composable<LoggingSettingsRoute> { LoggingSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<SpeedTestSettingsRoute> { SpeedTestSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<CaptureSettingsRoute> { CaptureSettingsScreen(onBack = { navController.popBackStack() }) }
        composable<CapabilitiesRoute> { CapabilitiesScreen(onBack = { navController.popBackStack() }) }
    }
}
