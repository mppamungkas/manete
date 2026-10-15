package id.my.pamslab.netlogger.feature.map.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import id.my.pamslab.netlogger.feature.map.MapScreen
import kotlinx.serialization.Serializable

/** [sessionId] null: the newest session, which follows a newly started recording. */
@Serializable
data class MapRoute(val sessionId: Long? = null)

fun NavController.navigateToMap(sessionId: Long? = null, navOptions: NavOptions? = null) = navigate(MapRoute(sessionId), navOptions)

fun NavGraphBuilder.mapScreen() {
    composable<MapRoute> { MapScreen() }
}
