package id.my.pamslab.netlogger.feature.sessions.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import id.my.pamslab.netlogger.feature.sessions.SessionsScreen
import kotlinx.serialization.Serializable

@Serializable
data object SessionsRoute

fun NavController.navigateToSessions(navOptions: NavOptions? = null) = navigate(SessionsRoute, navOptions)

fun NavGraphBuilder.sessionsScreen() {
    composable<SessionsRoute> { SessionsScreen() }
}
