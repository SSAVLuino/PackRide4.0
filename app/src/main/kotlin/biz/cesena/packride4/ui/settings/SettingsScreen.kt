package biz.cesena.packride4.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import biz.cesena.packride4.ui.home.SidebarDebugLogDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenMapManager: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val useOfflineMap by viewModel.useOfflineMap.collectAsState()
    val voiceMode by viewModel.voiceMode.collectAsState()
    var showDebugLog by remember { mutableStateOf(false) }

    if (showDebugLog) {
        SidebarDebugLogDialog(onDismiss = { showDebugLog = false })
    }

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
            // ── Sezione mappa ───────────────────────────────────────────────────
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
                            "Usa le mappe scaricate sul dispositivo."
                        else
                            "Scarica le tile da internet (CartoDB)."
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

            ListItem(
                headlineContent = { Text("Gestione mappe offline") },
                supportingContent = { Text("Scarica o elimina regioni per l'uso offline") },
                leadingContent = {
                    Icon(Icons.Default.Download, null,
                        tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier.clickable { onOpenMapManager() }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Sezione navigazione ────────────────────────────────────────────
            Text(
                "Navigazione",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            ListItem(
                headlineContent = { Text("Indicazioni vocali") },
                supportingContent = {
                    Text(when (voiceMode) {
                        "both" -> "Entrambi gli annunci"
                        "first_only" -> "Solo preparazione (es. \"Tra 400m...\")"
                        "second_only" -> "Solo manovra imminente"
                        "none" -> "Disattivate"
                        else -> "Entrambi gli annunci"
                    })
                },
                leadingContent = {
                    Icon(Icons.Default.VolumeUp, null,
                        tint = MaterialTheme.colorScheme.primary)
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val options = listOf("both" to "Entrambi", "first_only" to "Solo 1°", "second_only" to "Solo 2°", "none" to "Off")
                options.forEach { (value, label) ->
                    FilterChip(
                        selected = voiceMode == value,
                        onClick = { viewModel.setVoiceMode(value) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Sezione debug ───────────────────────────────────────────────────
            if (biz.cesena.packride4.BuildConfig.DEBUG) {
                Text(
                    "Sviluppo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )

                ListItem(
                    headlineContent = { Text("Log di debug") },
                    supportingContent = { Text("Visualizza i log dell'applicazione") },
                    leadingContent = {
                        Icon(Icons.Default.BugReport, null,
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable { showDebugLog = true }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
