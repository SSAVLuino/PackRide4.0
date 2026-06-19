package biz.cesena.packride4.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import biz.cesena.packride4.ui.home.HomeScreen
import biz.cesena.packride4.ui.mapmanager.MapManagerScreen
import biz.cesena.packride4.ui.savedroutes.SavedRoutesScreen
import biz.cesena.packride4.ui.settings.SettingsScreen
import biz.cesena.packride4.ui.theme.SidebarBackground

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object MapManager : Screen("map_manager")
    object SavedRoutes : Screen("saved_routes")
    object Settings : Screen("settings")
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
        SidebarItem(Screen.Home, "Mappa", Icons.Default.Map),
        SidebarItem(Screen.SavedRoutes, "Percorsi", Icons.Default.Route),
        SidebarItem(Screen.Settings, "Impostazioni", Icons.Default.Settings),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val sidebarWidth by animateDpAsState(
        targetValue = if (sidebarExpanded) SIDEBAR_EXPANDED_WIDTH else SIDEBAR_COLLAPSED_WIDTH,
        animationSpec = tween(durationMillis = 200),
        label = "sidebarWidth"
    )

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Content ──
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (currentRoute != Screen.Home.route) SIDEBAR_COLLAPSED_WIDTH else 0.dp)
        ) {
            composable(Screen.Home.route) {
                HomeScreen()
            }
            composable(Screen.MapManager.route) {
                MapManagerScreen()
            }
            composable(Screen.SavedRoutes.route) {
                SavedRoutesScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onOpenMapManager = {
                        navController.navigate(Screen.MapManager.route) {
                            popUpTo(Screen.Home.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        // ── Collapsible sidebar (left overlay) ──
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(sidebarWidth)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                .background(SidebarBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .windowInsetsPadding(WindowInsets.displayCutout)
            ) {

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
                    val isSelected = currentRoute == item.screen.route ||
                            (item.screen == Screen.Settings && currentRoute == Screen.MapManager.route)
                    SidebarNavItem(
                        item = item,
                        isSelected = isSelected,
                        expanded = sidebarExpanded,
                        onClick = {
                            if (item.screen == Screen.Home) {
                                navController.popBackStack(Screen.Home.route, inclusive = false)
                            } else {
                                navController.navigate(item.screen.route) {
                                    popUpTo(Screen.Home.route) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
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
