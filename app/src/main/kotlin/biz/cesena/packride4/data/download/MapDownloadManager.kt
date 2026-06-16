package biz.cesena.packride4.data.download

import android.content.Context
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.MapRegion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val GEOFABRIK = "https://download.geofabrik.de"

data class RegionCatalogEntry(
    val id: String,
    val name: String,
    val country: String,
    val downloadUrl: String,
    val fileName: String,
    val estimatedSizeMb: Double,
    val bbox: String,
    val routingGraphUrl: String? = null
)

val AVAILABLE_REGIONS = listOf(
    RegionCatalogEntry(
        id = "italia-nord-ovest",
        name = "Italia Nord-Ovest",
        country = "Italia",
        downloadUrl = "$GEOFABRIK/europe/italy/nord-ovest-shortbread-1.0.mbtiles",
        fileName = "italia-nord-ovest.mbtiles",
        estimatedSizeMb = 300.0,
        bbox = "6.6,43.8,12.5,46.7",
        routingGraphUrl = "https://github.com/SSAVLuino/PackRide4.0/releases/download/routing-graph-italia-nord-ovest-v3/graph-italia-nord-ovest.zip"
    ),
    RegionCatalogEntry(
        id = "italia-nord-est",
        name = "Italia — Nord Est",
        country = "Italia",
        downloadUrl = "$GEOFABRIK/europe/italy/nord-est-shortbread-1.0.mbtiles",
        fileName = "italia-nord-est.mbtiles",
        estimatedSizeMb = 600.0,
        bbox = "9.2,44.8,14.0,47.1"
    ),
    RegionCatalogEntry(
        id = "italia-centro",
        name = "Italia — Centro",
        country = "Italia",
        downloadUrl = "$GEOFABRIK/europe/italy/centro-shortbread-1.0.mbtiles",
        fileName = "italia-centro.mbtiles",
        estimatedSizeMb = 500.0,
        bbox = "9.7,41.2,14.8,44.5"
    ),
    RegionCatalogEntry(
        id = "italia-sud",
        name = "Italia — Sud",
        country = "Italia",
        downloadUrl = "$GEOFABRIK/europe/italy/sud-shortbread-1.0.mbtiles",
        fileName = "italia-sud.mbtiles",
        estimatedSizeMb = 450.0,
        bbox = "11.0,37.9,18.6,42.0"
    ),
    RegionCatalogEntry(
        id = "italia-isole",
        name = "Italia — Isole",
        country = "Italia",
        downloadUrl = "$GEOFABRIK/europe/italy/isole-shortbread-1.0.mbtiles",
        fileName = "italia-isole.mbtiles",
        estimatedSizeMb = 350.0,
        bbox = "8.1,36.6,15.7,38.3"
    ),
    RegionCatalogEntry(
        id = "svizzera",
        name = "Svizzera",
        country = "Svizzera",
        downloadUrl = "$GEOFABRIK/europe/switzerland-shortbread-1.0.mbtiles",
        fileName = "svizzera.mbtiles",
        estimatedSizeMb = 400.0,
        bbox = "5.9,45.8,10.5,47.8",
        routingGraphUrl = "https://github.com/SSAVLuino/PackRide4.0/releases/download/routing-graph-svizzera-v3/graph-svizzera.zip"
    ),
)

/**
 * Holds offline-map downloads in an application-scoped coroutine, so a download keeps
 * running even when the user navigates away from the map manager screen (its ViewModel
 * would otherwise be cleared and cancel the download's coroutine scope).
 */
@Singleton
class MapDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val routingManager: biz.cesena.packride4.routing.RoutingManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set by [downloadToFile] when it fails, so callers can log technical details. */
    private var lastDownloadError: String? = null

    private val _progress = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val progress: StateFlow<Map<String, Int?>> = _progress.asStateFlow()

    // null = not present, -1 = building graph, 0-100 = download progress
    private val _routingProgress = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val routingProgress: StateFlow<Map<String, Int?>> = _routingProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    fun reportError(message: String) {
        _errorMessage.value = message
    }

    fun startDownload(regionId: String) {
        if (_progress.value[regionId] != null) return // already downloading
        scope.launch {
            setProgress(regionId, 0)
            try {
                val entry = AVAILABLE_REGIONS.find { it.id == regionId } ?: return@launch
                val mapsDir = File(context.filesDir, "maps").also { it.mkdirs() }
                val destFile = File(mapsDir, entry.fileName)

                val success = downloadToFile(entry.downloadUrl, destFile) { pct ->
                    setProgress(regionId, pct)
                }
                if (!success) {
                    destFile.delete()
                    setProgress(regionId, null)
                    _errorMessage.value = "Download di ${entry.name} interrotto — riprova"
                    biz.cesena.packride4.debug.DebugLog.log("download ${entry.name} FAILED: ${lastDownloadError ?: "unknown error"}")
                    return@launch
                }

                biz.cesena.packride4.debug.DebugLog.log("download ${entry.name} OK, ${destFile.length()}B at ${destFile.absolutePath}")

                val bboxParts = entry.bbox.split(",").mapNotNull { it.toDoubleOrNull() }
                db.mapRegionDao().insert(
                    MapRegion(
                        id = regionId,
                        name = entry.name,
                        filePath = destFile.absolutePath,
                        downloadedAt = System.currentTimeMillis(),
                        sizeBytes = destFile.length(),
                        bboxMinLon = bboxParts.getOrElse(0) { 0.0 },
                        bboxMinLat = bboxParts.getOrElse(1) { 0.0 },
                        bboxMaxLon = bboxParts.getOrElse(2) { 0.0 },
                        bboxMaxLat = bboxParts.getOrElse(3) { 0.0 }
                    )
                )
                setProgress(regionId, null)
            } catch (e: Exception) {
                setProgress(regionId, null)
                biz.cesena.packride4.debug.DebugLog.log("download error: ${e.message}")
            }
        }
    }

    private fun setProgress(regionId: String, progress: Int?) {
        _progress.update { current ->
            if (progress == null) current - regionId else current + (regionId to progress)
        }
    }

    private fun setRoutingProgress(regionId: String, progress: Int?) {
        _routingProgress.update { current ->
            if (progress == null) current - regionId else current + (regionId to progress)
        }
    }

    /** Downloads the prebuilt routing graph archive for [regionId] (if defined) and extracts it. */
    fun startRoutingDownload(regionId: String) {
        if (_routingProgress.value[regionId] != null) return
        val entry = AVAILABLE_REGIONS.find { it.id == regionId } ?: return
        val graphUrl = entry.routingGraphUrl ?: return
        scope.launch {
            setRoutingProgress(regionId, 0)
            try {
                val routingDir = File(context.filesDir, "routing").also { it.mkdirs() }
                val zipFile = File(routingDir, "graph-$regionId.zip")

                val success = downloadToFile(graphUrl, zipFile) { pct ->
                    setRoutingProgress(regionId, pct)
                }
                if (!success) {
                    zipFile.delete()
                    setRoutingProgress(regionId, null)
                    _errorMessage.value = "Download dati di navigazione per ${entry.name} interrotto — riprova"
                    biz.cesena.packride4.debug.DebugLog.log("routing graph download ${entry.name} FAILED: ${lastDownloadError ?: "unknown error"}")
                    return@launch
                }
                biz.cesena.packride4.debug.DebugLog.log("routing graph download ${entry.name} OK, ${zipFile.length()}B")

                setRoutingProgress(regionId, -1) // extracting graph
                val graphDir = File(routingDir, "graph-$regionId")
                extractZip(zipFile, graphDir)
                zipFile.delete()

                // If the zip had a single top-level wrapper folder, flatten it.
                val children = graphDir.listFiles() ?: emptyArray()
                if (children.size == 1 && children[0].isDirectory) {
                    val wrapper = children[0]
                    wrapper.listFiles()?.forEach { child ->
                        child.renameTo(File(graphDir, child.name))
                    }
                    wrapper.delete()
                }
                biz.cesena.packride4.debug.DebugLog.log("routing graph extracted to ${graphDir.absolutePath}")

                routingManager.loadPrebuiltGraph(graphDir)
                if (!routingManager.isReady.value) {
                    setRoutingProgress(regionId, null)
                    _errorMessage.value = "Caricamento dati di navigazione per ${entry.name} non riuscito"
                    biz.cesena.packride4.debug.DebugLog.log("routing graph load ${entry.name} FAILED: isReady still false")
                    return@launch
                }
                setRoutingProgress(regionId, null)
            } catch (e: Exception) {
                setRoutingProgress(regionId, null)
                biz.cesena.packride4.debug.DebugLog.log("routing download/extract error: ${e.message}")
            }
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().buffered().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Streams [url] to [destFile] using a plain HttpURLConnection — Ktor's Android
     * engine buffers the whole response in memory before returning, which OOMs
     * for the 300-700MB mbtiles files downloaded here.
     */
    private fun downloadToFile(url: String, destFile: File, onProgress: (Int) -> Unit): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            var currentUrl = url
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 600_000
                    instanceFollowRedirects = false
                }
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return false
                    conn.disconnect()
                    currentUrl = location
                    if (++redirects > 5) return false
                    continue
                }
                connection = conn
                break
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: 1L
            var bytesWritten = 0L
            var lastReportedPct = -1

            connection.inputStream.use { input ->
                destFile.outputStream().buffered().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytesWritten += read
                        val pct = ((bytesWritten.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 99)
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("MapDownloadManager", "Download failed for $url", e)
            lastDownloadError = "${e::class.simpleName}: ${e.message}"
            false
        } finally {
            connection?.disconnect()
        }
    }
}
