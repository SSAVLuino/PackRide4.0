package biz.cesena.packride4.ui.home

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import biz.cesena.packride4.data.download.AVAILABLE_REGIONS
import biz.cesena.packride4.data.download.RegionCatalogEntry
import biz.cesena.packride4.routing.RoutingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ── Data ─────────────────────────────────────────────────────────────────────

data class StorageSummary(
    val tilesMb: Long,
    val graphsMb: Long,
    val geocodingMb: Long,
    val freeSpaceMb: Long,
    val totalSpaceMb: Long,
) {
    val usedByAppMb get() = tilesMb + graphsMb + geocodingMb
}

data class RegionDiagnostics(
    val region: RegionCatalogEntry,
    val tileFile: File,
    val tileExists: Boolean,
    val tileSizeMb: Long,
    val graphDir: File,
    val graphExists: Boolean,
    val graphLoaded: Boolean,
    val graphHasGraphHopper: Boolean,
    val graphSizeMb: Long,
    val geocodingFile: File?,
    val geocodingExists: Boolean,
    val geocodingSizeMb: Long,
    val geocodingRecords: Long,
    val geocodingDirFiles: List<String>,
)

// ── ViewModel helper ──────────────────────────────────────────────────────────

private fun dirSizeMb(dir: File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024

suspend fun buildDiagnostics(
    context: Context,
    routingManager: RoutingManager,
): Pair<StorageSummary, List<RegionDiagnostics>> = withContext(Dispatchers.IO) {
    val mapsDir = File(context.filesDir, "maps")
    val routingDir = File(context.filesDir, "routing")
    val geocodingDir = File(context.filesDir, "geocoding")
    val loadedIds = routingManager.loadedRegionIds()

    val allDbFiles = geocodingDir.listFiles()?.filter { it.name.endsWith(".db") }?.map { it.name } ?: emptyList()

    // Deduplicate shared graph dirs so we don't double-count
    val seenRoutingIds = mutableSetOf<String>()
    val seenGeocodingIds = mutableSetOf<String>()

    val regions = AVAILABLE_REGIONS.map { region ->
        val tileFile = File(mapsDir, region.fileName)
        val tileExists = tileFile.exists() && tileFile.length() > 0
        val tileSizeMb = tileFile.length() / 1024 / 1024

        val routingId = region.routingCountryId ?: region.id
        val graphDir = File(routingDir, "graph-$routingId")
        val graphExists = graphDir.exists() && graphDir.isDirectory && graphDir.listFiles()?.isNotEmpty() == true
        val graphLoaded = routingId in loadedIds
        val graphHasGraphHopper = File(graphDir, "properties").exists() ||
            graphDir.listFiles()?.any { it.name.endsWith(".properties") || it.name == "edges" } == true
        val graphSizeMb = if (graphExists && seenRoutingIds.add(routingId)) dirSizeMb(graphDir) else 0L

        val geocodingId = region.geocodingCountryId ?: region.id
        val geocodingFile: File? = File(geocodingDir, "geocoding-$geocodingId.db").takeIf { it.exists() }
            ?: geocodingDir.listFiles()?.firstOrNull { it.name.endsWith(".db") && it.name.contains(geocodingId) }
        val geocodingExists = geocodingFile?.exists() == true && geocodingFile.length() > 0
        val geocodingSizeMb = if (geocodingExists && seenGeocodingIds.add(geocodingId))
            (geocodingFile!!.length() / 1024 / 1024) else 0L
        val geocodingRecords = if (geocodingExists) {
            try {
                val db = SQLiteDatabase.openDatabase(geocodingFile!!.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val cursor = db.rawQuery("SELECT COUNT(*) FROM places", null)
                val count = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                cursor.close(); db.close(); count
            } catch (_: Exception) { -1L }
        } else 0L

        RegionDiagnostics(
            region, tileFile, tileExists, tileSizeMb,
            graphDir, graphExists, graphLoaded, graphHasGraphHopper, graphSizeMb,
            geocodingFile, geocodingExists, geocodingSizeMb, geocodingRecords,
            geocodingDirFiles = allDbFiles,
        )
    }

    // Storage summary
    val tilesMb = regions.sumOf { it.tileSizeMb }
    val graphsMb = regions.sumOf { it.graphSizeMb }
    val geocodingMb = regions.sumOf { it.geocodingSizeMb }
    val stat = StatFs(context.filesDir.absolutePath)
    val freeSpaceMb = stat.availableBytes / 1024 / 1024
    val totalSpaceMb = stat.totalBytes / 1024 / 1024

    Pair(
        StorageSummary(tilesMb, graphsMb, geocodingMb, freeSpaceMb, totalSpaceMb),
        regions,
    )
}

// ── UI ────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    routingManager: RoutingManager,
    context: Context,
    onBack: () -> Unit,
) {
    var result by remember { mutableStateOf<Pair<StorageSummary, List<RegionDiagnostics>>?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        result = buildDiagnostics(context, routingManager)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostica") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                actions = {
                    if (!loading) {
                        TextButton(onClick = {
                            loading = true
                            result = null
                        }) { Text("Aggiorna") }
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Verifica in corso…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            LaunchedEffect(loading) {
                if (loading && result == null) {
                    result = buildDiagnostics(context, routingManager)
                    loading = false
                }
            }
        } else {
            val (storage, diagnostics) = result ?: return@Scaffold
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { StorageCard(storage) }
                items(diagnostics) { diag -> RegionCard(diag) }
            }
        }
    }
}

// ── Storage summary card ──────────────────────────────────────────────────────

@Composable
private fun StorageCard(s: StorageSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Storage, null, modifier = Modifier.size(20.dp))
                Text("Spazio su disco", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(thickness = 0.5.dp)

            val usedPct = if (s.totalSpaceMb > 0) s.usedByAppMb * 100 / s.totalSpaceMb else 0L
            LinearProgressIndicator(
                progress = { if (s.totalSpaceMb > 0) s.usedByAppMb.toFloat() / s.totalSpaceMb else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("App: ${formatMb(s.usedByAppMb)} ($usedPct%)",
                    style = MaterialTheme.typography.bodySmall)
                Text("Libero: ${formatMb(s.freeSpaceMb)} / ${formatMb(s.totalSpaceMb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.freeSpaceMb < 500) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider(thickness = 0.5.dp)
            StorageRow("Mappe tiles", s.tilesMb)
            StorageRow("Grafi routing", s.graphsMb)
            StorageRow("DB geocoding", s.geocodingMb)
        }
    }
}

@Composable
private fun StorageRow(label: String, mb: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(formatMb(mb), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun formatMb(mb: Long): String = when {
    mb >= 1024 -> "${"%.1f".format(mb / 1024.0)} GB"
    else -> "$mb MB"
}

// ── Region card ───────────────────────────────────────────────────────────────

@Composable
private fun RegionCard(diag: RegionDiagnostics) {
    val overallOk = diag.tileExists &&
        (diag.region.routingGraphUrl == null || (diag.graphExists && diag.graphLoaded)) &&
        diag.geocodingExists

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (overallOk)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (overallOk) Icons.Default.CheckCircle else Icons.Default.Error,
                    null,
                    tint = if (overallOk) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(diag.region.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }

            HorizontalDivider(thickness = 0.5.dp)

            DiagRow(
                label = "Mappa tiles",
                ok = diag.tileExists,
                detail = if (diag.tileExists) "${formatMb(diag.tileSizeMb)}  (${diag.tileFile.name})"
                         else "NON TROVATA  (${diag.tileFile.absolutePath})",
            )

            if (diag.region.routingGraphUrl != null) {
                DiagRow(
                    label = "Grafo routing",
                    ok = diag.graphExists && diag.graphLoaded,
                    detail = when {
                        !diag.graphExists -> "NON TROVATO  (${diag.graphDir.absolutePath})"
                        !diag.graphHasGraphHopper -> "CARTELLA VUOTA o CORROTTA"
                        !diag.graphLoaded -> "Trovato ma NON CARICATO in memoria"
                        diag.graphSizeMb > 0 -> "Caricato · ${formatMb(diag.graphSizeMb)}  (${diag.graphDir.name})"
                        else -> "Caricato  (${diag.graphDir.name})"
                    },
                    warning = diag.graphExists && diag.graphHasGraphHopper && !diag.graphLoaded,
                )
            }

            DiagRow(
                label = "DB geocoding",
                ok = diag.geocodingExists,
                detail = if (diag.geocodingExists)
                    "${formatMb(diag.geocodingSizeMb)} · ${diag.geocodingRecords.let { if (it >= 0) "$it record" else "errore lettura" }}  (${diag.geocodingFile?.name})"
                else if (diag.geocodingDirFiles.isEmpty())
                    "NON TROVATO — cartella geocoding vuota"
                else
                    "NON TROVATO (cercato: geocoding-${diag.region.geocodingCountryId ?: diag.region.id}.db) — presenti: ${diag.geocodingDirFiles.joinToString(", ")}",
            )
        }
    }
}

@Composable
private fun DiagRow(label: String, ok: Boolean, detail: String, warning: Boolean = false) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            when {
                ok -> Icons.Default.CheckCircle
                warning -> Icons.Default.HourglassEmpty
                else -> Icons.Default.Error
            },
            null,
            tint = when {
                ok -> Color(0xFF388E3C)
                warning -> Color(0xFFF57C00)
                else -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
