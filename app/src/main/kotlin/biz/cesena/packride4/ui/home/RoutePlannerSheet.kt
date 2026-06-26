package biz.cesena.packride4.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import biz.cesena.packride4.routing.GeocodingResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerSheet(
    waypoints: List<RouteWaypoint>,
    editingIndex: Int,
    searchQuery: String,
    searchResults: List<GeocodingResult>,
    searchLoading: Boolean,
    onClose: () -> Unit,
    onAddWaypoint: () -> Unit,
    onRemoveWaypoint: (Int) -> Unit,
    onStartEditing: (Int) -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectResult: (GeocodingResult) -> Unit,
    onCalculate: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

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
                        enabled = waypoints.count { it.isSet } >= 2
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
            // ── Waypoint list (skip index 0 = GPS start) ────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                waypoints.forEachIndexed { index, wp ->
                    if (index == 0) return@forEachIndexed

                    val isLast = index == waypoints.size - 1
                    val isIntermediate = !isLast
                    val label = if (isLast) "Destinazione" else "Tappa $index"
                    val iconTint = when {
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
                                    .focusRequester(focusRequester),
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
                                color = if (wp.isSet)
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
                                    Icon(Icons.Default.Place, null, tint = iconTint, modifier = Modifier.size(22.dp))
                                    Text(
                                        text = if (wp.isSet) wp.label.ifBlank { label } else "Tocca per impostare $label",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (wp.isSet) FontWeight.Medium else FontWeight.Normal,
                                        color = if (wp.isSet)
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isIntermediate) {
                                        IconButton(
                                            onClick = { onRemoveWaypoint(index) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Close, "Rimuovi",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (isLast) {
                                IconButton(onClick = onAddWaypoint) {
                                    Icon(Icons.Default.Add, "Aggiungi tappa",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            // ── Search results (when editing a waypoint) ─────────────────────
            if (editingIndex >= 0) {
                HorizontalDivider()
                if (searchLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
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
