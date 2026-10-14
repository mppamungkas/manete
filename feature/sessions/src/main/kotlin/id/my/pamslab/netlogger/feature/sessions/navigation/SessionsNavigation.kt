package id.my.pamslab.netlogger.feature.sessions.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import id.my.pamslab.netlogger.feature.sessions.SessionDetailScreen
import id.my.pamslab.netlogger.feature.sessions.SessionsScreen
import kotlinx.serialization.Serializable

@Serializable
data object SessionsRoute

@Serializable
data class SessionDetailRoute(val id: Long)

fun NavController.navigateToSessions(navOptions: NavOptions? = null) = navigate(SessionsRoute, navOptions)

fun NavController.navigateToSessionDetail(id: Long) = navigate(SessionDetailRoute(id))

fun NavGraphBuilder.sessionsScreen(onOpenSession: (Long) -> Unit, onShowOnMap: (Long) -> Unit, onBack: () -> Unit) {
    composable<SessionsRoute> { SessionsScreen(onOpen = onOpenSession) }
    composable<SessionDetailRoute> { SessionDetailScreen(onBack = onBack, onShowOnMap = onShowOnMap) }
}
