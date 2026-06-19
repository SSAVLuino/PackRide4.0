package biz.cesena.packride4.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val useOfflineMap by viewModel.useOfflineMap.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Impostazioni") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Sezione mappa ───────────────────────────────────────────────────────
            Text(
                "Mappa",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            ListItem(
                headlineContent = {
                    Text(if (useOfflineMap) "Mappe offline" else "Mappe online")
                },
                supportingContent = {
                    Text(
                        if (useOfflineMap)
                            "Usa le mappe scaricate sul dispositivo. Senza connessione internet."
                        else
                            "Scarica le tile da internet (CartoDB). Richiede connessione."
                    )
                },
                trailingContent = {
                    Switch(
                        checked = useOfflineMap,
                        onCheckedChange = { viewModel.setUseOfflineMap(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
