package id.my.pamslab.netlogger.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import id.my.pamslab.netlogger.feature.capture.CaptureRow
import id.my.pamslab.netlogger.feature.logger.navigation.LiveRoute
import id.my.pamslab.netlogger.feature.logger.navigation.liveScreen
import id.my.pamslab.netlogger.feature.map.navigation.MapRoute
import id.my.pamslab.netlogger.feature.map.navigation.mapScreen
import id.my.pamslab.netlogger.feature.sessions.navigation.SessionsRoute
import id.my.pamslab.netlogger.feature.sessions.navigation.navigateToSessionDetail
import id.my.pamslab.netlogger.feature.sessions.navigation.sessionsScreen
import id.my.pamslab.netlogger.feature.settings.navigation.SettingsGraph
import id.my.pamslab.netlogger.feature.settings.navigation.navigateToProblem
import id.my.pamslab.netlogger.feature.settings.navigation.settingsGraph
import id.my.pamslab.netlogger.permissions.PermissionScreen
import id.my.pamslab.netlogger.permissions.PermissionViewModel
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object PermissionsRoute

private enum class TopLevelDestination(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector) {
    LIVE(LiveRoute, LiveRoute::class, "Live", Icons.Filled.PlayArrow),
    MAP(MapRoute(), MapRoute::class, "Map", Icons.Filled.Place),
    SESSIONS(SessionsRoute, SessionsRoute::class, "Sessions", Icons.AutoMirrored.Filled.List),
    SETTINGS(SettingsGraph, SettingsGraph::class, "Settings", Icons.Filled.Settings),
}

@Composable
fun NetLoggerApp(
    permissionViewModel: PermissionViewModel = hiltViewModel(),
    /** The open screen's route name, for problem reports (decision 005). */
    onScreen: (String?) -> Unit = {},
) {
    val navController = rememberNavController()
    val permissionState by permissionViewModel.state.collectAsStateWithLifecycle()
    // Show the rationale once per launch, on top of Live, if logging permissions are missing.
    var rationaleShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!rationaleShown && !permissionViewModel.state.value.canLog) {
            rationaleShown = true
            navController.navigate(PermissionsRoute)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    LaunchedEffect(currentDestination) {
        // "id.my…MapRoute?sessionId={sessionId}" -> "MapRoute"
        onScreen(currentDestination?.route?.substringBefore('?')?.substringAfterLast('.'))
    }
    val showBottomBar = currentDestination?.hasRoute(PermissionsRoute::class) == false

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                // Tapping the open tab again leaves a sub-page (e.g. Settings › Logging) for the tab's
                                // first screen; restoreState alone would bring the sub-page back. A tab without
                                // sub-pages stays as it is: navigating would rebuild its screen, and destroying a
                                // MapView that way crashes MapLibre's renderer (moto g45, 2026-10-16).
                                val graph = if (!selected) null else runCatching {
                                    navController.getBackStackEntry(destination.route).destination as? NavGraph
                                }.getOrNull()
                                when {
                                    graph != null -> navController.popBackStack(graph.startDestinationId, inclusive = false)
                                    !selected -> navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = LiveRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<PermissionsRoute> {
                PermissionScreen(
                    viewModel = permissionViewModel,
                    onContinue = { navController.popBackStack() },
                )
            }
            liveScreen(
                loggingPermissionsMissing = { !permissionState.canLog },
                onRequestPermissions = { navController.navigate(PermissionsRoute) },
                captureRow = { label -> CaptureRow(label) },
                onOpenProblem = { navController.navigateToProblem(it) },
            )
            mapScreen()
            sessionsScreen(
                onOpenSession = navController::navigateToSessionDetail,
                onShowOnMap = { id ->
                    // A new Map entry with this session; the bottom bar shows Map as selected.
                    navController.navigate(MapRoute(id)) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
            settingsGraph(navController)
        }
    }
}
