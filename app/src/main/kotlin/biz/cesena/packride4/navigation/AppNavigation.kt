package biz.cesena.packride4.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import biz.cesena.packride4.R
import biz.cesena.packride4.ui.auth.AuthScreen
import biz.cesena.packride4.ui.home.HomeScreen
import biz.cesena.packride4.ui.mapmanager.MapManagerScreen
import biz.cesena.packride4.ui.rides.RidesScreen
import biz.cesena.packride4.ui.routing.RoutingScreen
import biz.cesena.packride4.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object MapManager : Screen("map_manager")
    object Routing : Screen("routing")
    object Rides : Screen("rides")
    object Auth : Screen("auth")
    object Settings : Screen("settings")
}

private data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    val bottomNavItems = listOf(
        BottomNavItem(Screen.Home, "Mappa", Icons.Default.Map),
        BottomNavItem(Screen.MapManager, "Mappe", Icons.Default.DirectionsBike),
        BottomNavItem(Screen.Rides, "Uscite", Icons.Default.People),
        BottomNavItem(Screen.Settings, "Impostazioni", Icons.Default.Settings),
    )

    // Routes that should NOT show the bottom bar
    val fullscreenRoutes = setOf(Screen.Auth.route, Screen.Routing.route)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val showBottomBar = currentDestination?.route !in fullscreenRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToRouting = { navController.navigate(Screen.Routing.route) }
                )
            }
            composable(Screen.MapManager.route) {
                MapManagerScreen()
            }
            composable(Screen.Routing.route) {
                RoutingScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Rides.route) {
                RidesScreen(
                    onNavigateToAuth = { navController.navigate(Screen.Auth.route) }
                )
            }
            composable(Screen.Auth.route) {
                AuthScreen(
                    onAuthSuccess = {
                        navController.navigate(Screen.Rides.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
