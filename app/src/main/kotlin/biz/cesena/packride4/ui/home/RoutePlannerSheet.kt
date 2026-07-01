package biz.cesena.packride4.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import biz.cesena.packride4.data.prefs.FavoritePlace
import biz.cesena.packride4.data.prefs.RecentDestination
import biz.cesena.packride4.routing.GeocodingResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerSheet(
    waypoints: List<RouteWaypoint>,
    editingIndex: Int,
    searchQuery: String,
    searchResults: List<GeocodingResult>,
    searchLoading: Boolean,
    recentDestinations: List<RecentDestination> = emptyList(),
    favorites: List<FavoritePlace> = emptyList(),
    onSaveFavorite: (FavoritePlace) -> Unit = {},
    onClose: () -> Unit,
    onAddWaypoint: () -> Unit,
    onRemoveWaypoint: (Int) -> Unit,
    onStartEditing: (Int) -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectResult: (GeocodingResult) -> Unit,
    onResetOriginToGps: () -> Unit,
    onCalculate: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var saveFavTarget by remember { mutableStateOf<RouteWaypoint?>(null) }

    LaunchedEffect(editingIndex) {
        if (editingIndex >= 0) {
            try { focusRequester.requestFocus(); keyboard?.show() } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pianifica percorso") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Chiudi")
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f)
                    ) { Text("Annulla") }
                    Button(
                        onClick = onCalculate,
                        modifier = Modifier.weight(1f),
                        enabled = waypoints.count { it.isSet || it.isGps } >= 2
                    ) {
                        Icon(Icons.Default.Navigation, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Calcola")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Waypoint list ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                waypoints.forEachIndexed { index, wp ->
                    val isOrigin = index == 0
                    val isLast = index == waypoints.size - 1
                    val isIntermediate = !isOrigin && !isLast
                    val label = when {
                        isOrigin -> "Partenza"
                        isLast -> "Destinazione"
                        else -> "Tappa $index"
                    }
                    val iconTint = when {
                        isOrigin -> MaterialTheme.colorScheme.primary
                        isLast -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    }

                    if (editingIndex == index) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = onSearchChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(if (index == editingIndex) focusRequester else FocusRequester.Default),
                                placeholder = { Text("Cerca $label…") },
                                leadingIcon = { Icon(Icons.Default.Place, null, tint = iconTint) },
                                trailingIcon = {
                                    IconButton(onClick = { onStartEditing(-1) }) {
                                        Icon(Icons.Default.Close, "Annulla")
                                    }
                                },
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                            )
                            if (isLast) {
                                IconButton(onClick = onAddWaypoint) {
                                    Icon(Icons.Default.Add, "Aggiungi tappa",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium,
                                color = if (wp.isSet || wp.isGps)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                onClick = { onStartEditing(index) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        if (isOrigin && wp.isGps) Icons.Default.GpsFixed else Icons.Default.Place,
                                        null, tint = iconTint, modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = when {
                                            isOrigin && wp.isGps -> "Posizione GPS corrente"
                                            wp.isSet -> wp.label.ifBlank { label }
                                            else -> "Tocca per impostare $label"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (wp.isSet || wp.isGps) FontWeight.Medium else FontWeight.Normal,
                                        color = if (wp.isSet || wp.isGps)
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            // Star: save set non-GPS waypoint as favorite
                            if (wp.isSet && !wp.isGps) {
                                val isFaved = favorites.any { f ->
                                    kotlin.math.abs(f.lat - wp.lat) < 0.0001 &&
                                    kotlin.math.abs(f.lon - wp.lon) < 0.0001
                                }
                                IconButton(onClick = { if (!isFaved) saveFavTarget = wp }) {
                                    Icon(Icons.Default.Star, "Salva come preferito",
                                        tint = if (isFaved) androidx.compose.ui.graphics.Color(0xFFFFD700)
                                               else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // Remove button for intermediate waypoints
                            if (isIntermediate) {
                                IconButton(onClick = { onRemoveWaypoint(index) }) {
                                    Icon(Icons.Default.Close, "Rimuovi",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // Origin: reset-to-GPS button when a custom start is set
                            if (isOrigin && !wp.isGps) {
                                IconButton(onClick = onResetOriginToGps) {
                                    Icon(Icons.Default.GpsFixed, "Usa posizione GPS",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            // Last waypoint: add next destination button
                            if (isLast && !isOrigin) {
                                IconButton(onClick = onAddWaypoint) {
                                    Icon(Icons.Default.Add, "Aggiungi tappa",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            // ── Recents / search results (when editing a waypoint) ───────────
            if (editingIndex >= 0) {
                HorizontalDivider()
                if (searchLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val showSuggestions = searchQuery.isBlank()
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (showSuggestions) {
                        if (favorites.isNotEmpty()) {
                            item {
                                Text("Preferiti",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            }
                            items(favorites) { fav ->
                                ListItem(
                                    headlineContent = { Text("${fav.icon}  ${fav.name}", maxLines = 1) },
                                    modifier = Modifier.clickable {
                                        onSelectResult(GeocodingResult(
                                            name = fav.name, address = "", lat = fav.lat, lon = fav.lon, distanceKm = 0.0))
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                        if (recentDestinations.isNotEmpty()) {
                            item {
                                Text("Recenti",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            }
                            items(recentDestinations) { recent ->
                                ListItem(
                                    headlineContent = { Text(recent.name, maxLines = 1) },
                                    leadingContent = {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = androidx.compose.ui.Modifier
                                                .size(20.dp)
                                                .graphicsLayer(rotationZ = 225f))
                                    },
                                    modifier = Modifier.clickable {
                                        onSelectResult(GeocodingResult(
                                            name = recent.name, address = "", lat = recent.lat, lon = recent.lon, distanceKm = 0.0))
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    } else {
                        items(searchResults) { result ->
                            ListItem(
                                headlineContent = { Text(result.name, maxLines = 1) },
                                supportingContent = {
                                    Text(result.address,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Place, null,
                                        tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = {
                                    if (result.distanceKm > 0) {
                                        Text(
                                            if (result.distanceKm >= 1.0) "${"%.0f".format(result.distanceKm)} km"
                                            else "${(result.distanceKm * 1000).toInt()} m",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { onSelectResult(result) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    saveFavTarget?.let { wp ->
        SaveFavoriteDialog(
            suggestedName = wp.label,
            lat = wp.lat,
            lon = wp.lon,
            onDismiss = { saveFavTarget = null },
            onSave = { fav ->
                onSaveFavorite(fav)
                saveFavTarget = null
            }
        )
    }
}
