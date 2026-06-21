package biz.cesena.packride4.ui.mapmanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Screen for managing offline map regions.
 * Users can browse available regions, download them for offline use, and delete downloaded data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapManagerScreen(
    viewModel: MapManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Mobile data warning dialog
    uiState.showMobileDataWarning?.let { regionId ->
        val region = uiState.regions.find { it.id == regionId }
        val sizeMb = region?.sizeMb ?: 0.0
        AlertDialog(
            onDismissRequest = viewModel::dismissMobileDataWarning,
            title = { Text("Stai usando dati mobili") },
            text = { Text("Il file è ~%.0f MB. Vuoi procedere con il download su rete mobile?".format(sizeMb)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMobileDataDownload) { Text("Procedi") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMobileDataWarning) { Text("Annulla") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mappe offline") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Error message banner
            uiState.errorMessage?.let { msg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (uiState.regions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Total storage used
                val totalMb = uiState.regions
                    .filter { it.isDownloaded }
                    .sumOf { it.sizeMb }
                if (totalMb > 0) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            "Spazio occupato: %.1f MB".format(totalMb),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.regions, key = { it.id }) { region ->
                        MapRegionCard(
                            region = region,
                            onDownload = { viewModel.downloadRegion(region.id) },
                            onDelete = { viewModel.deleteRegion(region.id) },
                            onDownloadRouting = { viewModel.downloadRoutingData(region.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapRegionCard(
    region: MapRegionUi,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDownloadRouting: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(region.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "%.0f MB · %s".format(region.sizeMb, region.bbox),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (region.downloadProgress != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { region.downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${region.downloadProgress}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            when {
                region.downloadProgress != null -> {
                    // Download in progress — no button
                }
                region.isDownloaded -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Scaricata",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Elimina",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                else -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "Scarica",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (region.isDownloaded && region.hasRoutingPbf) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    region.isRoutingReady -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Routing pronto",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Navigazione offline pronta", style = MaterialTheme.typography.bodySmall)
                    }
                    region.routingProgress == -1 -> {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Elaborazione dati di navigazione...", style = MaterialTheme.typography.bodySmall)
                    }
                    region.routingProgress != null -> {
                        LinearProgressIndicator(
                            progress = { region.routingProgress / 100f },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${region.routingProgress}%", style = MaterialTheme.typography.labelSmall)
                    }
                    else -> {
                        TextButton(onClick = onDownloadRouting) {
                            Text("Scarica dati per la navigazione")
                        }
                    }
                }
            }
        }

        }
    }
}
