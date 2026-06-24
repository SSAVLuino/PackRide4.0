package biz.cesena.packride4.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import biz.cesena.packride4.ui.auth.LoginScreen
import biz.cesena.packride4.ui.home.HomeScreen
import biz.cesena.packride4.ui.mapmanager.MapManagerScreen
import biz.cesena.packride4.ui.savedroutes.SavedRoutesScreen
import biz.cesena.packride4.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object MapManager : Screen("map_manager")
    object SavedRoutes : Screen("saved_routes")
    object Settings : Screen("settings")
    object Login : Screen("login")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToMaps = {
                    navController.navigate(Screen.MapManager.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNavigateToRoutes = {
                    navController.navigate(Screen.SavedRoutes.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Screen.MapManager.route) {
            MapManagerScreen(
                onLoginRequired = {
                    navController.navigate(Screen.Login.route) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
                onClose = { navController.popBackStack(Screen.Home.route, inclusive = false) },
            )
        }
        composable(Screen.SavedRoutes.route) {
            SavedRoutesScreen(
                onGoToMap = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                onBack = { navController.popBackStack() },
                onClose = { navController.popBackStack(Screen.Home.route, inclusive = false) },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onOpenMapManager = {
                    navController.navigate(Screen.MapManager.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
                onClose = { navController.popBackStack(Screen.Home.route, inclusive = false) },
            )
        }
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.MapManager.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
