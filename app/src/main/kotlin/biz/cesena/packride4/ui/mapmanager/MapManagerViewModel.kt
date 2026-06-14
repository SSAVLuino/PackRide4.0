package biz.cesena.packride4.ui.mapmanager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.download.AVAILABLE_REGIONS
import biz.cesena.packride4.data.download.MapDownloadManager
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.utils.ConnectivityUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapRegionUi(
    val id: String,
    val name: String,
    val sizeMb: Double,
    val bbox: String,
    val isDownloaded: Boolean,
    val downloadProgress: Int? = null,  // null = not downloading; 0-100 = in progress
    val hasRoutingPbf: Boolean = false,
    val routingProgress: Int? = null,   // null = idle; -1 = building graph; 0-100 = downloading
    val isRoutingReady: Boolean = false
)

data class MapManagerUiState(
    val regions: List<MapRegionUi> = emptyList(),
    val showMobileDataWarning: String? = null,   // regionId pending confirmation
    val errorMessage: String? = null
)

private data class DownloadState(
    val downloaded: List<biz.cesena.packride4.data.local.MapRegion>,
    val progress: Map<String, Int?>,
    val routingProgress: Map<String, Int?>,
    val error: String?,
    val mobileWarning: String?
)

@HiltViewModel
class MapManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val downloadManager: MapDownloadManager,
    private val routingManager: biz.cesena.packride4.routing.RoutingManager
) : ViewModel() {

    private val _showMobileDataWarning = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MapManagerUiState> = combine(
        combine(
            db.mapRegionDao().getAll(),
            downloadManager.progress,
            downloadManager.routingProgress,
            downloadManager.errorMessage,
            _showMobileDataWarning
        ) { downloaded, progress, routingProgress, error, mobileWarning ->
            DownloadState(downloaded, progress, routingProgress, error, mobileWarning)
        },
        routingManager.isReady
    ) { state, routingReady ->
        val (downloaded, progress, routingProgress, error, mobileWarning) = state

        val downloadedIds = downloaded.map { it.id }.toSet()
        val regions = AVAILABLE_REGIONS.map { entry ->
            val entity = downloaded.find { it.id == entry.id }
            MapRegionUi(
                id = entry.id,
                name = entry.name,
                sizeMb = entity?.sizeBytes?.div(1_048_576.0) ?: entry.estimatedSizeMb,
                bbox = entry.bbox,
                isDownloaded = entry.id in downloadedIds,
                downloadProgress = progress[entry.id],
                hasRoutingPbf = entry.routingPbfUrl != null,
                routingProgress = routingProgress[entry.id],
                isRoutingReady = routingReady
            )
        }
        MapManagerUiState(regions = regions, showMobileDataWarning = mobileWarning, errorMessage = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MapManagerUiState())

    fun confirmMobileDataDownload() {
        val regionId = _showMobileDataWarning.value ?: return
        _showMobileDataWarning.value = null
        downloadManager.startDownload(regionId)
    }

    fun dismissMobileDataWarning() {
        _showMobileDataWarning.value = null
    }

    fun downloadRegion(regionId: String) {
        when {
            !ConnectivityUtils.isConnected(context) -> {
                downloadManager.reportError("Connessione assente — impossibile scaricare la mappa")
            }
            !ConnectivityUtils.isWifi(context) -> {
                _showMobileDataWarning.value = regionId
            }
            else -> downloadManager.startDownload(regionId)
        }
    }

    fun downloadRoutingData(regionId: String) {
        downloadManager.startRoutingDownload(regionId)
    }

    fun deleteRegion(regionId: String) {
        viewModelScope.launch {
            val entity = db.mapRegionDao().getById(regionId) ?: return@launch
            java.io.File(entity.filePath).takeIf { it.exists() }?.delete()
            db.mapRegionDao().deleteById(regionId)
        }
    }
}
