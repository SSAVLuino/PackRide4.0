package biz.cesena.packride4.ui.home

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
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

data class RegionDiagnostics(
    val region: RegionCatalogEntry,
    val tileFile: File,
    val tileExists: Boolean,
    val tileSizeMb: Long,
    val graphDir: File,
    val graphExists: Boolean,
    val graphLoaded: Boolean,
    val graphHasGraphHopper: Boolean,
    val geocodingFile: File?,
    val geocodingExists: Boolean,
    val geocodingSizeMb: Long,
    val geocodingRecords: Long,
)

// ── ViewModel helper (suspend fn, called from LaunchedEffect) ─────────────────

suspend fun buildDiagnostics(
    context: Context,
    routingManager: RoutingManager,
): List<RegionDiagnostics> = withContext(Dispatchers.IO) {
    val mapsDir = File(context.filesDir, "maps")
    val routingDir = File(context.filesDir, "routing")
    val geocodingDir = File(context.filesDir, "geocoding")
    val loadedIds = routingManager.loadedRegionIds()

    AVAILABLE_REGIONS.map { region ->
        val tileFile = File(mapsDir, region.fileName)
        val tileExists = tileFile.exists() && tileFile.length() > 0
        val tileSizeMb = tileFile.length() / 1024 / 1024

        val graphDir = File(routingDir, "graph-${region.id}")
        val graphExists = graphDir.exists() && graphDir.isDirectory && graphDir.listFiles()?.isNotEmpty() == true
        val graphLoaded = region.id in loadedIds
        // Check that the GraphHopper-specific metadata file is present
        val graphHasGraphHopper = File(graphDir, "properties").exists() ||
            graphDir.listFiles()?.any { it.name.endsWith(".properties") || it.name == "edges" } == true

        // Geocoding DB: named after region id
        val geocodingFile: File? = File(geocodingDir, "geocoding-${region.id}.db").takeIf { it.exists() }
            ?: geocodingDir.listFiles()?.firstOrNull { it.name.endsWith(".db") && it.name.contains(region.id) }
        val geocodingExists = geocodingFile?.exists() == true && geocodingFile.length() > 0
        val geocodingSizeMb = geocodingFile?.length()?.div(1024 * 1024) ?: 0L
        val geocodingRecords = if (geocodingExists) {
            try {
                val db = SQLiteDatabase.openDatabase(geocodingFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val cursor = db.rawQuery("SELECT COUNT(*) FROM places", null)
                val count = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                cursor.close(); db.close(); count
            } catch (_: Exception) { -1L }
        } else 0L

        RegionDiagnostics(
            region, tileFile, tileExists, tileSizeMb,
            graphDir, graphExists, graphLoaded, graphHasGraphHopper,
            geocodingFile, geocodingExists, geocodingSizeMb, geocodingRecords,
        )
    }
}

// ── UI ────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    routingManager: RoutingManager,
    context: Context,
    onBack: () -> Unit,
) {
    var diagnostics by remember { mutableStateOf<List<RegionDiagnostics>?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        diagnostics = buildDiagnostics(context, routingManager)
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
                            diagnostics = null
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
            // Re-trigger after diagnostics cleared
            LaunchedEffect(loading) {
                if (loading && diagnostics == null) {
                    diagnostics = buildDiagnostics(context, routingManager)
                    loading = false
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(diagnostics ?: emptyList()) { diag ->
                    RegionCard(diag)
                }
            }
        }
    }
}

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

            // Tiles
            DiagRow(
                label = "Mappa tiles",
                ok = diag.tileExists,
                detail = if (diag.tileExists) "${diag.tileSizeMb} MB  (${diag.tileFile.name})"
                         else "NON TROVATA  (${diag.tileFile.absolutePath})",
            )

            // Routing graph (only if available for this region)
            if (diag.region.routingGraphUrl != null) {
                DiagRow(
                    label = "Grafo routing",
                    ok = diag.graphExists && diag.graphLoaded,
                    detail = when {
                        !diag.graphExists -> "NON TROVATO  (${diag.graphDir.absolutePath})"
                        !diag.graphHasGraphHopper -> "CARTELLA VUOTA o CORROTTA"
                        !diag.graphLoaded -> "Trovato ma NON CARICATO in memoria"
                        else -> "Caricato  (${diag.graphDir.name})"
                    },
                    warning = diag.graphExists && diag.graphHasGraphHopper && !diag.graphLoaded,
                )
            }

            // Geocoding DB
            DiagRow(
                label = "DB geocoding",
                ok = diag.geocodingExists,
                detail = if (diag.geocodingExists)
                    "${diag.geocodingSizeMb} MB · ${diag.geocodingRecords.let { if (it >= 0) "$it record" else "errore lettura" }}  (${diag.geocodingFile?.name})"
                else
                    "NON TROVATO  (cerca in: ${File(diag.geocodingFile?.parent ?: "geocoding/", "${diag.region.id}.db")})",
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
