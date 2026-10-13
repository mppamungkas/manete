package id.my.pamslab.netlogger.feature.map.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import id.my.pamslab.netlogger.feature.map.MapScreen
import kotlinx.serialization.Serializable

@Serializable
data object MapRoute

fun NavController.navigateToMap(navOptions: NavOptions? = null) = navigate(MapRoute, navOptions)

fun NavGraphBuilder.mapScreen() {
    composable<MapRoute> { MapScreen() }
}
