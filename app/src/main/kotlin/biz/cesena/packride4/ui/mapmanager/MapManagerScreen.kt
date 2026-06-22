package biz.cesena.packride4.ui.mapmanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import biz.cesena.packride4.data.download.MapCountry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapManagerScreen(
    onLoginRequired: () -> Unit = {},
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
        topBar = { TopAppBar(title = { Text("Mappe offline") }) }
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
                    items(uiState.countries, key = { it.id }) { country ->
                        CountryCard(
                            country = country,
                            regions = uiState.regions.filter { it.countryId == country.id },
                            onDownloadRouting = { viewModel.downloadRoutingData(country.id) },
                            onDownloadGeocoding = { viewModel.downloadGeocodingData(country.id) },
                            onDeleteCountryData = { viewModel.deleteCountryData(country.id) },
                            onDownloadRegion = { viewModel.downloadRegion(it) },
                            onDeleteRegion = { viewModel.deleteRegion(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryCard(
    country: MapCountry,
    regions: List<MapRegionUi>,
    onDownloadRouting: () -> Unit,
    onDownloadGeocoding: () -> Unit,
    onDeleteCountryData: () -> Unit,
    onDownloadRegion: (String) -> Unit,
    onDeleteRegion: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // ── Country header (clickable to expand) ──
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

            // ── Country data downloads (routing + geocoding) ──
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Routing graph
                if (country.graphUrl != null) {
                    CountryDataRow(
                        label = "Dati navigazione",
                        sizeMb = country.graphSizeMb,
                        onDownload = onDownloadRouting,
                        onDelete = onDeleteCountryData
                    )
                }

                // Geocoding DB
                if (country.geocodingUrl != null) {
                    CountryDataRow(
                        label = "Dati ricerca",
                        sizeMb = country.geocodingSizeMb,
                        onDownload = onDownloadGeocoding,
                        onDelete = null
                    )
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
                        onDelete = { onDeleteRegion(region.id) }
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CountryDataRow(
    label: String,
    sizeMb: Double,
    onDownload: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (sizeMb > 0) {
                Text("%.0f MB".format(sizeMb), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onDownload) { Text("Scarica") }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "Elimina", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RegionRow(
    region: MapRegionUi,
    onDownload: () -> Unit,
    onDelete: () -> Unit
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
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
