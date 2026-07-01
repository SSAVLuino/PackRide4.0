package biz.cesena.packride4.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import biz.cesena.packride4.data.prefs.FavoritePlace
import java.util.UUID

val FAVORITE_ICONS = listOf("🏠","💼","⭐","🏋️","🛒","⛽","🏥","🏫","🍕","☕","🏖️","🏔️","🎭","🏟️","⛪","🚂","✈️","⚓")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favorites: List<FavoritePlace>,
    onBack: () -> Unit,
    onSave: (FavoritePlace) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editingFav by remember { mutableStateOf<FavoritePlace?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preferiti") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                actions = {}
            )
        }
    ) { padding ->
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nessun preferito. Tieni premuto sulla mappa per aggiungerne uno.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(favorites, key = { it.id }) { fav ->
                    ListItem(
                        headlineContent = { Text("${fav.icon}  ${fav.name}", fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            Text("%.5f, %.5f".format(fav.lat, fav.lon),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { editingFav = fav }) {
                                    Icon(Icons.Default.Edit, "Modifica", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onDelete(fav.id) }) {
                                    Icon(Icons.Default.Delete, "Elimina", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editingFav?.let { fav ->
        FavoriteEditDialog(
            initial = fav,
            onDismiss = { editingFav = null },
            onConfirm = { name, icon ->
                onSave(fav.copy(name = name, icon = icon))
                editingFav = null
            },
            onConfirmWithCoords = null,
        )
    }
}

@Composable
fun FavoriteEditDialog(
    initial: FavoritePlace?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String) -> Unit,
    onConfirmWithCoords: ((name: String, icon: String) -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var selectedIcon by remember { mutableStateOf(initial?.icon ?: "⭐") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuovo preferito" else "Modifica preferito") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Icona", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FAVORITE_ICONS) { icon ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (icon == selectedIcon)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { selectedIcon = icon }
                        ) {
                            Text(icon, modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedIcon) },
                enabled = name.isNotBlank()
            ) { Text("Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}

/**
 * Small bottom sheet that appears after a long press on the map.
 * Shows the coordinates and lets the user save the point as a favorite.
 */
@Composable
fun LongPressSheet(
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    onSaveFavorite: (FavoritePlace) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false, onClick = {}),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "%.5f, %.5f".format(lat, lon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Salva come preferito")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Annulla") }
            }
        }
    }

    if (showSaveDialog) {
        SaveFavoriteDialog(
            suggestedName = "",
            lat = lat,
            lon = lon,
            onDismiss = { showSaveDialog = false; onDismiss() },
            onSave = onSaveFavorite,
        )
    }
}
/** Dialog shown when saving a search result as a favorite — name/icon pre-filled. */
@Composable
fun SaveFavoriteDialog(
    suggestedName: String,
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    onSave: (FavoritePlace) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var selectedIcon by remember { mutableStateOf("⭐") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salva come preferito") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Icona", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FAVORITE_ICONS) { icon ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (icon == selectedIcon)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { selectedIcon = icon }
                        ) {
                            Text(icon, modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(FavoritePlace(UUID.randomUUID().toString(), name.trim(), selectedIcon, lat, lon))
                    }
                },
                enabled = name.isNotBlank()
            ) { Text("Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}
