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

/** Catalogue of downloadable regions — in a real app these would come from a remote API. */
private val AVAILABLE_REGIONS = listOf(
    Triple("italy",          "Italia",              "6.0,36.5,19.0,47.5"),
    Triple("emilia-romagna", "Emilia-Romagna",       "9.2,43.7,12.8,45.1"),
    Triple("tuscany",        "Toscana",              "9.7,42.3,12.4,44.5"),
    Triple("lombardy",       "Lombardia",            "8.5,44.6,11.4,46.7"),
    Triple("veneto",         "Veneto",               "10.6,44.8,13.1,46.7"),
    Triple("sicily",         "Sicilia",              "11.9,36.6,15.7,38.3"),
    Triple("sardinia",       "Sardegna",             "8.1,38.8,9.9,41.3"),
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
                val regions = AVAILABLE_REGIONS.map { (id, name, bbox) ->
                    val entity = downloaded.find { it.id == id }
                    MapRegionUi(
                        id = id,
                        name = name,
                        sizeMb = entity?.sizeBytes?.div(1_048_576.0) ?: estimateSizeMb(id),
                        bbox = bbox,
                        isDownloaded = id in downloadedIds
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
                val mapsDir = File(context.filesDir, "maps").also { it.mkdirs() }
                val destFile = File(mapsDir, "$regionId.mbtiles")

                // TODO: replace with real MBTiles CDN URL
                val downloadUrl = "https://cdn.packride.cesena.biz/maps/$regionId.mbtiles"

                val response: HttpResponse = httpClient.get(downloadUrl)
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
                db.mapRegionDao().insert(
                    MapRegion(
                        id = regionId,
                        name = AVAILABLE_REGIONS.find { it.first == regionId }?.second ?: regionId,
                        filePath = destFile.absolutePath,
                        downloadedAt = System.currentTimeMillis(),
                        sizeBytes = destFile.length(),
                        bboxMinLon = 0.0, bboxMinLat = 0.0, bboxMaxLon = 0.0, bboxMaxLat = 0.0
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

    private fun estimateSizeMb(regionId: String): Double = when (regionId) {
        "italy" -> 850.0
        "emilia-romagna" -> 85.0
        "tuscany" -> 90.0
        "lombardy" -> 95.0
        "veneto" -> 75.0
        "sicily" -> 80.0
        "sardinia" -> 60.0
        else -> 100.0
    }
}
