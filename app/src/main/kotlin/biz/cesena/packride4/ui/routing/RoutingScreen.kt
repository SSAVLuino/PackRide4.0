package biz.cesena.packride4.ui.routing

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Screen to set a destination and start offline turn-by-turn navigation via GraphHopper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    onBack: () -> Unit,
    viewModel: RoutingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigazione") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Destination input
            OutlinedTextField(
                value = uiState.destinationQuery,
                onValueChange = viewModel::onDestinationQueryChange,
                label = { Text("Destinazione") },
                placeholder = { Text("Indirizzo, città o lat,lon…") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = viewModel::searchDestination) {
                        Icon(Icons.Default.Search, contentDescription = "Cerca")
                    }
                },
                singleLine = true
            )

            // Route summary
            uiState.routeSummary?.let { summary ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Percorso calcolato",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column {
                                Text("Distanza", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    summary.distanceKm,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column {
                                Text("Tempo stimato", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    summary.eta,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            uiState.errorMessage?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (uiState.isCalculating) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Calcolo percorso offline…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = viewModel::startNavigation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = uiState.routeSummary != null && !uiState.isNavigating
            ) {
                Icon(Icons.Default.Navigation, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Avvia navigazione")
            }

            if (uiState.isNavigating) {
                OutlinedButton(
                    onClick = viewModel::stopNavigation,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Ferma navigazione")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
