package biz.cesena.packride4.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import biz.cesena.packride4.ui.theme.SidebarBackground
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import biz.cesena.packride4.ui.createride.CreateRideScreen
import biz.cesena.packride4.ui.home.HomeScreen
import biz.cesena.packride4.ui.mapmanager.MapManagerScreen
import biz.cesena.packride4.ui.profile.ProfileScreen
import biz.cesena.packride4.ui.routing.RoutingScreen
import biz.cesena.packride4.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object CreateRide : Screen("create_ride")
    object Settings : Screen("settings")
    object Profile : Screen("profile")
    object MapManager : Screen("map_manager")
    object Routing : Screen("routing")
}

private data class SidebarItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

private val SIDEBAR_COLLAPSED_WIDTH = 64.dp
private val SIDEBAR_EXPANDED_WIDTH = 220.dp


@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    var sidebarExpanded by remember { mutableStateOf(false) }

    val sidebarItems = listOf(
        SidebarItem(Screen.Home, "Home", Icons.Default.Map),
        SidebarItem(Screen.CreateRide, "Crea uscita", Icons.Default.AddRoad),
        SidebarItem(Screen.Settings, "Impostazioni", Icons.Default.Settings),
        SidebarItem(Screen.Profile, "Profilo", Icons.Default.Person),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Routes where the sidebar is hidden (fullscreen experiences)
    val hideSidebarRoutes = setOf(Screen.Routing.route)
    val showSidebar = currentRoute !in hideSidebarRoutes

    val sidebarWidth by animateDpAsState(
        targetValue = if (sidebarExpanded) SIDEBAR_EXPANDED_WIDTH else SIDEBAR_COLLAPSED_WIDTH,
        animationSpec = tween(durationMillis = 200),
        label = "sidebarWidth"
    )

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Content (NavHost fills all space; left padding reserves room for sidebar except on map) ──
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (showSidebar && currentRoute != Screen.Home.route) SIDEBAR_COLLAPSED_WIDTH else 0.dp)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToRouting = { navController.navigate(Screen.Routing.route) }
                )
            }
            composable(Screen.CreateRide.route) {
                CreateRideScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToMapManager = { navController.navigate(Screen.MapManager.route) }
                )
            }
            composable(Screen.Profile.route) {
                ProfileScreen()
            }
            composable(Screen.MapManager.route) {
                MapManagerScreen()
            }
            composable(Screen.Routing.route) {
                RoutingScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // ── NavigationInstructionsBar placeholder (bottom overlay, home only) ──
        if (currentRoute == Screen.Home.route) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(80.dp)
            )
        }

        // ── Collapsible sidebar (left overlay) ──
        if (showSidebar) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(sidebarWidth)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                    .background(SidebarBackground)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    // Toggle button at top
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SIDEBAR_COLLAPSED_WIDTH)
                            .clickable { sidebarExpanded = !sidebarExpanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (sidebarExpanded) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                            contentDescription = if (sidebarExpanded) "Comprimi menu" else "Espandi menu",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    // Nav items
                    sidebarItems.forEach { item ->
                        val isSelected = currentRoute == item.screen.route
                        SidebarNavItem(
                            item = item,
                            isSelected = isSelected,
                            expanded = sidebarExpanded,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarNavItem(
    item: SidebarItem,
    isSelected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected)
        Color.White.copy(alpha = 0.15f)
    else
        Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SIDEBAR_COLLAPSED_WIDTH)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(26.dp)
        )
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = item.label,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
