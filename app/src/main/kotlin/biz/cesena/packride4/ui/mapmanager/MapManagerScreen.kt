package biz.cesena.packride4.ui.mapmanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapManagerScreen(
    onLoginRequired: () -> Unit = {},
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    viewModel: MapManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    uiState.showMobileDataWarning?.let { regionId ->
        val region = uiState.regions.find { it.id == regionId }
        AlertDialog(
            onDismissRequest = viewModel::dismissMobileDataWarning,
            title = { Text("Stai usando dati mobili") },
            text = { Text("Il file è ~%.0f MB. Vuoi procedere con il download su rete mobile?".format(region?.sizeMb ?: 0.0)) },
            confirmButton = { TextButton(onClick = viewModel::confirmMobileDataDownload) { Text("Procedi") } },
            dismissButton = { TextButton(onClick = viewModel::dismissMobileDataWarning) { Text("Annulla") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mappe offline") },
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
            if (!uiState.isLoggedIn) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Accedi per scaricare le mappe offline", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onLoginRequired) { Text("Accedi") }
                    }
                }
                return@Scaffold
            }

            // Error banner — dismissible
            uiState.errorMessage?.let { msg ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Chiudi", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.countries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nessuna mappa disponibile", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.countries, key = { it.country.id }) { countryUi ->
                        CountryCard(
                            countryUi = countryUi,
                            regions = uiState.regions.filter { it.countryId == countryUi.country.id },
                            onDownloadRouting = { viewModel.downloadRoutingData(countryUi.country.id) },
                            onDownloadGeocoding = { viewModel.downloadGeocodingData(countryUi.country.id) },
                            onDeleteRouting = { viewModel.deleteCountryData(countryUi.country.id) },
                            onDeleteGeocoding = { viewModel.deleteGeocodingData(countryUi.country.id) },
                            onDownloadRegion = { viewModel.downloadRegion(it) },
                            onDeleteRegion = { viewModel.deleteRegion(it) },
                            onUpdateRouting = { viewModel.updateRoutingData(countryUi.country.id) },
                            onUpdateGeocoding = { viewModel.updateGeocodingData(countryUi.country.id) },
                            onUpdateRegion = { viewModel.updateRegion(it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryCard(
    countryUi: CountryUi,
    regions: List<MapRegionUi>,
    onDownloadRouting: () -> Unit,
    onDownloadGeocoding: () -> Unit,
    onDeleteRouting: () -> Unit,
    onDeleteGeocoding: () -> Unit,
    onDownloadRegion: (String) -> Unit,
    onDeleteRegion: (String) -> Unit,
    onUpdateRouting: () -> Unit = {},
    onUpdateGeocoding: () -> Unit = {},
    onUpdateRegion: (String) -> Unit = {},
) {
    val country = countryUi.country
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(country.name, style = MaterialTheme.typography.titleMedium)
                    val downloadedCount = regions.count { it.isDownloaded }
                    if (downloadedCount > 0) {
                        Text(
                            "$downloadedCount/${regions.size} regioni scaricate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                if (country.graphUrl != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dati navigazione", style = MaterialTheme.typography.bodyMedium)
                            if (country.graphSizeMb > 0) {
                                Text("%.0f MB".format(country.graphSizeMb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        when {
                            countryUi.isRoutingReady -> {
                                if (countryUi.hasRoutingUpdate) {
                                    TextButton(onClick = onUpdateRouting) { Text("Aggiorna") }
                                } else {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pronto", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = onDeleteRouting, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, "Elimina",
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                            countryUi.routingProgress == -1 -> {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Elaborazione...", style = MaterialTheme.typography.bodySmall)
                            }
                            countryUi.routingProgress != null -> {
                                Text("${countryUi.routingProgress}%", style = MaterialTheme.typography.labelSmall)
                            }
                            else -> {
                                TextButton(onClick = onDownloadRouting) { Text("Scarica") }
                            }
                        }
                    }
                }


                // Geocoding DB
                if (country.geocodingUrl != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dati ricerca", style = MaterialTheme.typography.bodyMedium)
                            if (country.geocodingSizeMb > 0) {
                                Text("%.0f MB".format(country.geocodingSizeMb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        when {
                            countryUi.isGeocodingReady -> {
                                if (countryUi.hasGeocodingUpdate) {
                                    TextButton(onClick = onUpdateGeocoding) { Text("Aggiorna") }
                                } else {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pronto", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = onDeleteGeocoding, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, "Elimina",
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                            countryUi.geocodingProgress == -1 -> {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Estrazione...", style = MaterialTheme.typography.bodySmall)
                            }
                            countryUi.geocodingProgress != null -> {
                                Text("${countryUi.geocodingProgress}%", style = MaterialTheme.typography.labelSmall)
                            }
                            else -> {
                                TextButton(onClick = onDownloadGeocoding) { Text("Scarica") }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // ── Regions list ──
                Text(
                    "Mappe regionali",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                regions.forEach { region ->
                    RegionRow(
                        region = region,
                        onDownload = { onDownloadRegion(region.id) },
                        onDelete = { onDeleteRegion(region.id) },
                        onUpdate = { onUpdateRegion(region.id) },
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RegionRow(
    region: MapRegionUi,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(region.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "%.0f MB".format(region.sizeMb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (region.downloadProgress != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { region.downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            region.downloadProgress != null -> {
                Text("${region.downloadProgress}%", style = MaterialTheme.typography.labelSmall)
            }
            region.isDownloaded -> {
                if (region.hasUpdate) {
                    TextButton(onClick = onUpdate) { Text("Aggiorna", fontSize = 12.sp) }
                } else {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Elimina", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
            else -> {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.CloudDownload, "Scarica", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
