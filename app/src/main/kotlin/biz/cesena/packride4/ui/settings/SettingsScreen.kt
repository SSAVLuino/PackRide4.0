package biz.cesena.packride4.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val prefs by viewModel.prefs.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Impostazioni") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // === Navigation ===
            Text("Navigazione", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            SettingRow(
                label = "Annunci vocali",
                description = "Legge le istruzioni di svolta ad alta voce"
            ) {
                Switch(
                    checked = prefs.voiceGuidanceEnabled,
                    onCheckedChange = viewModel::setVoiceGuidance
                )
            }

            SettingRow(
                label = "Velocità massima moto",
                description = "Usata dal routing per stimare i tempi"
            ) {
                var expanded by remember { mutableStateOf(false) }
                val options = listOf(90, 110, 130, 150)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = "${prefs.maxSpeedKph} km/h",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .width(120.dp)
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("$speed km/h") },
                                onClick = {
                                    viewModel.setMaxSpeed(speed)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // === GPS Tracking ===
            Text("Tracciamento GPS", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            SettingRow(
                label = "Intervallo registrazione",
                description = "Frequenza punti GPS nella traccia"
            ) {
                var expanded by remember { mutableStateOf(false) }
                val options = listOf(1, 2, 5, 10)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = "${prefs.gpsIntervalSeconds}s",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .width(100.dp)
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { sec ->
                            DropdownMenuItem(
                                text = { Text("${sec}s") },
                                onClick = {
                                    viewModel.setGpsInterval(sec)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            SettingRow(
                label = "Esporta GPX automaticamente",
                description = "Salva la traccia al termine della navigazione"
            ) {
                Switch(
                    checked = prefs.autoExportGpx,
                    onCheckedChange = viewModel::setAutoExportGpx
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // === Online / Gruppo ===
            Text("Modalità gruppo (online)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            SettingRow(
                label = "Soglia offline",
                description = "Minuti senza posizione prima di segnare un rider come offline"
            ) {
                var expanded by remember { mutableStateOf(false) }
                val options = listOf(5, 10, 15, 20, 30)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = "${prefs.offlineMinutes} min",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .width(110.dp)
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { m ->
                            DropdownMenuItem(
                                text = { Text("$m min") },
                                onClick = {
                                    viewModel.setOfflineMinutes(m)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            SettingRow(
                label = "Frequenza aggiornamento mappa",
                description = "Ogni quanti secondi recupera le posizioni del gruppo"
            ) {
                var expanded by remember { mutableStateOf(false) }
                val options = listOf(5, 10, 15, 30, 60)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = "${prefs.pollIntervalSeconds}s",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .width(100.dp)
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { s ->
                            DropdownMenuItem(
                                text = { Text("${s}s") },
                                onClick = {
                                    viewModel.setPollInterval(s)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    description: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}
