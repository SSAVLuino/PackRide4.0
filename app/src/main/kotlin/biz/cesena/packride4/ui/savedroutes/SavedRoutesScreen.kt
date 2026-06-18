package biz.cesena.packride4.ui.savedroutes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import biz.cesena.packride4.data.local.SavedRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SavedRoutesScreen(
    onNavigateTo: (lat: Double, lon: Double, name: String) -> Unit = { _, _, _ -> },
    viewModel: SavedRoutesViewModel = hiltViewModel()
) {
    val routes by viewModel.routes.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 64.dp) // sidebar clearance
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Text(
            "Percorsi salvati",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        )

        if (routes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nessun percorso salvato",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(routes, key = { it.id }) { route ->
                    SavedRouteItem(
                        route = route,
                        onNavigate = { onNavigateTo(route.destinationLat, route.destinationLon, route.name) },
                        onDelete = { viewModel.delete(route) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SavedRouteItem(
    route: SavedRoute,
    onNavigate: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(route.savedAt) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(route.savedAt))
    }
    val distanceStr = if (route.distanceMeters >= 1000)
        "${"%.1f".format(route.distanceMeters / 1000)} km"
    else "${route.distanceMeters.roundToInt()} m"
    val durationStr = run {
        val min = (route.durationMillis / 60_000).toInt()
        if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
    }

    ListItem(
        headlineContent = {
            Text(route.name, fontWeight = FontWeight.Medium, maxLines = 1)
        },
        supportingContent = {
            Text("$distanceStr  ·  $durationStr  ·  $dateStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onNavigate) {
                    Icon(Icons.Default.Navigation, "Naviga",
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Elimina",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}
