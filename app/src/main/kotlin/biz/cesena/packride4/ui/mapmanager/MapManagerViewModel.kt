package biz.cesena.packride4.ui.mapmanager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.MapRegion
import biz.cesena.packride4.utils.ConnectivityUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

data class MapRegionUi(
    val id: String,
    val name: String,
    val sizeMb: Double,
    val bbox: String,
    val isDownloaded: Boolean,
    val downloadProgress: Int? = null  // null = not downloading; 0-100 = in progress
)

data class MapManagerUiState(
    val regions: List<MapRegionUi> = emptyList(),
    val showMobileDataWarning: String? = null,   // regionId pending confirmation
    val errorMessage: String? = null
)

private const val GEOFABRIK = "https://download.geofabrik.de"

data class RegionCatalogEntry(
    val id: String,
    val name: String,
    val country: String,
    val downloadUrl: String,
    val fileName: String,
    val estimatedSizeMb: Double,
    val bbox: String
)

private val AVAILABLE_REGIONS = listOf(
    RegionCatalogEntry(
        id = "italia-nord-ovest",
        name = "Italia — Nord Ovest",
        country = "Italia",
        downloadUrl = "$GEOFABRIK/europe/italy/nord-ovest-shortbread-1.0.mbtiles",
        fileName = "italia-nord-ovest.mbtiles",
        estimatedSizeMb = 731.0,
        bbox = "6.6,43.8,9.2,46.5"
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
        bbox = "5.9,45.8,10.5,47.8"
    ),
)

@HiltViewModel
class MapManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapManagerUiState())
    val uiState: StateFlow<MapManagerUiState> = _uiState.asStateFlow()

    init {
        loadRegions()
    }

    private fun loadRegions() {
        viewModelScope.launch {
            db.mapRegionDao().getAll().collect { downloaded ->
                val downloadedIds = downloaded.map { it.id }.toSet()
                val regions = AVAILABLE_REGIONS.map { entry ->
                    val entity = downloaded.find { it.id == entry.id }
                    MapRegionUi(
                        id = entry.id,
                        name = entry.name,
                        sizeMb = entity?.sizeBytes?.div(1_048_576.0) ?: entry.estimatedSizeMb,
                        bbox = entry.bbox,
                        isDownloaded = entry.id in downloadedIds
                    )
                }
                _uiState.update { it.copy(regions = regions) }
            }
        }
    }

    fun confirmMobileDataDownload() {
        val regionId = _uiState.value.showMobileDataWarning ?: return
        _uiState.update { it.copy(showMobileDataWarning = null) }
        startDownload(regionId)
    }

    fun dismissMobileDataWarning() {
        _uiState.update { it.copy(showMobileDataWarning = null) }
    }

    fun downloadRegion(regionId: String) {
        when {
            !ConnectivityUtils.isConnected(context) -> {
                _uiState.update { it.copy(errorMessage = "Connessione assente — impossibile scaricare la mappa") }
                return
            }
            !ConnectivityUtils.isWifi(context) -> {
                _uiState.update { it.copy(showMobileDataWarning = regionId) }
                return
            }
            else -> startDownload(regionId)
        }
    }

    private fun startDownload(regionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
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
                    return@launch
                }

                // Persist to Room
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
            }
        }
    }

    fun deleteRegion(regionId: String) {
        viewModelScope.launch {
            val entity = db.mapRegionDao().getById(regionId) ?: return@launch
            File(entity.filePath).takeIf { it.exists() }?.delete()
            db.mapRegionDao().deleteById(regionId)
        }
    }

    private fun setProgress(regionId: String, progress: Int?) {
        _uiState.update { state ->
            state.copy(
                regions = state.regions.map { r ->
                    if (r.id == regionId) r.copy(
                        downloadProgress = progress,
                        isDownloaded = if (progress == null) {
                            // Check DB after download completes — keep current value for now
                            r.isDownloaded
                        } else r.isDownloaded
                    ) else r
                }
            )
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
                    connectTimeout = 15_000
                    readTimeout = 60_000
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
            false
        } finally {
            connection?.disconnect()
        }
    }
}
