package biz.cesena.packride4.ui.savedroutes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete

import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.ui.res.painterResource
import biz.cesena.packride4.ui.common.maneuverIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import biz.cesena.packride4.data.local.SavedRoute
import biz.cesena.packride4.debug.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesScreen(
    onGoToMap: () -> Unit = {},
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    viewModel: SavedRoutesViewModel = hiltViewModel()
) {
    val routes by viewModel.routes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Percorsi salvati") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Chiudi")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (routes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nessun percorso salvato",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(routes, key = { it.id }) { route ->
                        SavedRouteItem(
                            route = route,
                            onNavigate = {
                                DebugLog.log("SavedRoutesScreen: navigate clicked for route id=${route.id}")
                                viewModel.loadRoute(route)
                                onGoToMap()
                            },
                            onDelete = { viewModel.delete(route) }
                        )
                        HorizontalDivider()
                    }
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
    var expanded by remember { mutableStateOf(false) }
    val instructions = remember(route.instructionsJson) {
        SavedRoute.deserializeInstructions(route.instructionsJson)
    }
    val dateStr = remember(route.savedAt) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(route.savedAt))
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Header ──────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    route.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    "${formatDistance(route.distanceMeters)}  ·  ${formatDuration(route.durationMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onNavigate) {
                Icon(Icons.Default.Navigation, "Naviga verso destinazione",
                    tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Elimina percorso",
                    tint = MaterialTheme.colorScheme.error)
            }
        }

        // ── Pulsante "Mostra istruzioni" ────────────────────────────────────────
        if (instructions.isNotEmpty()) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .padding(start = 8.dp, bottom = 4.dp)
                    .height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (expanded) "Nascondi istruzioni" else "Mostra ${instructions.size} istruzioni",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // ── Lista istruzioni (espandibile) ──────────────────────────────────────
        if (expanded && instructions.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    instructions.forEach { instr ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                painter = painterResource(maneuverIcon(instr.sign, instr.modifier, instr.exitNumber, instr.turnAngle)),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = instr.text.ifBlank { "Prosegui" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = formatDistance(instr.distanceMeters),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> "${"%.1f".format(meters / 1000)} km"
    else           -> "${meters.roundToInt()} m"
}

private fun formatDuration(millis: Long): String {
    val totalMin = (millis / 60_000).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
