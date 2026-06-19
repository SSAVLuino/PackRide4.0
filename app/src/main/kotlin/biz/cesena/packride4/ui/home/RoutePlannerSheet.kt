package biz.cesena.packride4.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
                        Icon(Icons.Default.ArrowBack, "Chiudi")
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
            // ── Waypoint list ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                waypoints.forEachIndexed { index, wp ->
                    val isFirst = index == 0
                    val isLast = index == waypoints.size - 1
                    val isIntermediate = !isFirst && !isLast
                    val label = when {
                        isFirst -> "Partenza"
                        isLast -> "Destinazione"
                        else -> "Tappa ${index}"
                    }
                    val icon = when {
                        isFirst && wp.isGps -> Icons.Default.GpsFixed
                        isFirst -> Icons.Default.Place
                        else -> Icons.Default.Place
                    }
                    val iconTint = when {
                        isFirst -> MaterialTheme.colorScheme.primary
                        isLast -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    }

                    if (editingIndex == index) {
                        // Editing mode: search field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            placeholder = { Text("Cerca $label…") },
                            leadingIcon = { Icon(icon, null, tint = iconTint) },
                            trailingIcon = {
                                IconButton(onClick = { onStartEditing(-1) }) {
                                    Icon(Icons.Default.Close, "Annulla")
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                        )
                    } else {
                        // Display mode: show selected location or placeholder
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
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
                                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (wp.isSet) wp.label.ifBlank { label } else "Tocca per impostare $label",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (wp.isSet) FontWeight.Medium else FontWeight.Normal,
                                        color = if (wp.isSet)
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    if (wp.isSet && wp.isGps) {
                                        Text(
                                            "Posizione attuale",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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
                    }
                }

                // Add waypoint button
                TextButton(
                    onClick = onAddWaypoint,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Aggiungi tappa intermedia")
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
                            modifier = Modifier.clickable { onSelectResult(result) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
