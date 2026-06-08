package biz.cesena.packride4.ui.rides

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Online ride list screen — mirrors the Supabase `rides` table from the sibling app.
 * Requires the user to be logged in; otherwise shows a prompt to authenticate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesScreen(
    onNavigateToAuth: () -> Unit,
    viewModel: RidesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Le mie uscite") })
        },
        floatingActionButton = {
            if (uiState.isLoggedIn) {
                FloatingActionButton(onClick = viewModel::createRide) {
                    Icon(Icons.Default.Add, contentDescription = "Nuova uscita")
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                !uiState.isLoggedIn -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Accedi per vedere le uscite del gruppo",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onNavigateToAuth) {
                            Text("Accedi / Registrati")
                        }
                    }
                }

                uiState.rides.isEmpty() -> {
                    Text(
                        "Nessuna uscita — crea la prima!",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.rides, key = { it.id }) { ride ->
                            RideCard(ride = ride)
                        }
                    }
                }
            }

            uiState.errorMessage?.let { err ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text(err)
                }
            }
        }
    }
}

@Composable
private fun RideCard(ride: RideUi) {
    val dateStr = remember(ride.rideStart) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(Date(ride.rideStart))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(ride.name, style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (ride.status == "in_progress") "In corso" else "Conclusa",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (ride.status == "in_progress")
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Codice: ${ride.joinCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                dateStr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
