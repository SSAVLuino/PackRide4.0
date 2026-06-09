package biz.cesena.packride4.ui.mapmanager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.MapRegion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
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
    val regions: List<MapRegionUi> = emptyList()
)

private const val CDN_BASE = "https://pub-d9bdcaa84c63461eba5788c909b63b18.r2.dev"

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
        downloadUrl = "$CDN_BASE/Italia/nord-ovest-shortbread-1.0.mbtiles",
        fileName = "nord-ovest-shortbread-1.0.mbtiles",
        estimatedSizeMb = 731.0,
        bbox = "6.6,43.8,9.2,46.5"
    ),
    RegionCatalogEntry(
        id = "italia-nord-est",
        name = "Italia — Nord Est",
        country = "Italia",
        downloadUrl = "$CDN_BASE/Italia/nord-est-shortbread-1.0.mbtiles",
        fileName = "nord-est-shortbread-1.0.mbtiles",
        estimatedSizeMb = 600.0,
        bbox = "9.2,44.8,14.0,47.1"
    ),
    RegionCatalogEntry(
        id = "italia-centro",
        name = "Italia — Centro",
        country = "Italia",
        downloadUrl = "$CDN_BASE/Italia/centro-shortbread-1.0.mbtiles",
        fileName = "centro-shortbread-1.0.mbtiles",
        estimatedSizeMb = 500.0,
        bbox = "9.7,41.2,14.8,44.5"
    ),
    RegionCatalogEntry(
        id = "italia-sud",
        name = "Italia — Sud",
        country = "Italia",
        downloadUrl = "$CDN_BASE/Italia/sud-shortbread-1.0.mbtiles",
        fileName = "sud-shortbread-1.0.mbtiles",
        estimatedSizeMb = 450.0,
        bbox = "11.0,37.9,18.6,42.0"
    ),
    RegionCatalogEntry(
        id = "italia-isole",
        name = "Italia — Isole",
        country = "Italia",
        downloadUrl = "$CDN_BASE/Italia/isole-shortbread-1.0.mbtiles",
        fileName = "isole-shortbread-1.0.mbtiles",
        estimatedSizeMb = 350.0,
        bbox = "8.1,36.6,15.7,38.3"
    ),
    RegionCatalogEntry(
        id = "svizzera",
        name = "Svizzera",
        country = "Svizzera",
        downloadUrl = "$CDN_BASE/Switzerland/switzerland-shortbread-1.0.mbtiles",
        fileName = "switzerland-shortbread-1.0.mbtiles",
        estimatedSizeMb = 400.0,
        bbox = "5.9,45.8,10.5,47.8"
    ),
)

@HiltViewModel
class MapManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val httpClient: HttpClient
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

    fun downloadRegion(regionId: String) {
        viewModelScope.launch {
            setProgress(regionId, 0)
            try {
                val entry = AVAILABLE_REGIONS.find { it.id == regionId } ?: return@launch
                val mapsDir = File(context.filesDir, "maps").also { it.mkdirs() }
                val destFile = File(mapsDir, entry.fileName)

                val response: HttpResponse = httpClient.get(entry.downloadUrl)
                if (!response.status.isSuccess()) {
                    setProgress(regionId, null)
                    return@launch
                }

                val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 1L
                var bytesWritten = 0L

                destFile.outputStream().buffered().use { out ->
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytesWritten += read
                        val pct = ((bytesWritten.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 99)
                        setProgress(regionId, pct)
                    }
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

}
