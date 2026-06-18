package biz.cesena.packride4.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val useOfflineMap by viewModel.useOfflineMap.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 64.dp) // sidebar clearance
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Text(
            "Impostazioni",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        )

        HorizontalDivider()

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
