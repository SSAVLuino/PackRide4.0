package biz.cesena.packride4.ui.mapmanager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.auth.AuthRepository
import biz.cesena.packride4.data.download.MapCatalogRepository
import biz.cesena.packride4.data.download.MapCountry
import biz.cesena.packride4.data.download.MapDownloadManager
import biz.cesena.packride4.data.download.MapRegionRemote
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.utils.ConnectivityUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapRegionUi(
    val id: String,
    val countryId: String = "",
    val name: String,
    val sizeMb: Double,
    val bbox: String,
    val isDownloaded: Boolean,
    val downloadProgress: Int? = null,
    val hasRoutingPbf: Boolean = false,
    val routingProgress: Int? = null,
    val isRoutingReady: Boolean = false,
    val hasUpdate: Boolean = false,
)

data class CountryUi(
    val country: MapCountry,
    val isRoutingReady: Boolean = false,
    val routingProgress: Int? = null,
    val isGeocodingReady: Boolean = false,
    val geocodingProgress: Int? = null,
    val hasRoutingUpdate: Boolean = false,
    val hasGeocodingUpdate: Boolean = false,
)

data class MapManagerUiState(
    val isLoggedIn: Boolean = false,
    val regions: List<MapRegionUi> = emptyList(),
    val countries: List<CountryUi> = emptyList(),
    val showMobileDataWarning: String? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class MapManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val downloadManager: MapDownloadManager,
    private val routingManager: biz.cesena.packride4.routing.RoutingManager,
    private val authRepository: AuthRepository,
    private val catalogRepository: MapCatalogRepository
) : ViewModel() {

    private val _showMobileDataWarning = MutableStateFlow<String?>(null)
    private val _countries = MutableStateFlow<List<MapCountry>>(emptyList())
    private val _remoteRegions = MutableStateFlow<List<MapRegionRemote>>(emptyList())
    private val _isLoading = MutableStateFlow(false)

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
        routingManager.isReady,
        authRepository.isLoggedIn,
        combine(_countries, _remoteRegions, _isLoading, downloadManager.geocodingProgress) { c, r, l, gp ->
            object { val countries = c; val remoteRegions = r; val isLoading = l; val geocodingProgress = gp }
        }
    ) { downloadState, routingReady, isLoggedIn, extra ->
        val (downloaded, progress, routingProgress, error, mobileWarning) = downloadState
        val countries = extra.countries; val remoteRegions = extra.remoteRegions
        val isLoading = extra.isLoading; val geocodingProgress = extra.geocodingProgress

        val downloadedIds = downloaded.map { it.id }.toSet()
        val routingDir = java.io.File(context.filesDir, "routing")
        val regions = remoteRegions.map { entry ->
            val entity = downloaded.find { it.id == entry.id }
            val graphDir = java.io.File(routingDir, "graph-${entry.countryId}")
            val graphOnDisk = graphDir.exists() && graphDir.isDirectory && (graphDir.listFiles()?.isNotEmpty() == true)
            val country = countries.find { it.id == entry.countryId }
            MapRegionUi(
                id = entry.id,
                countryId = entry.countryId,
                name = entry.name,
                sizeMb = entity?.sizeBytes?.div(1_048_576.0) ?: entry.mbtilesSizeMb,
                bbox = entry.bbox,
                isDownloaded = entry.id in downloadedIds,
                downloadProgress = progress[entry.id],
                hasRoutingPbf = country?.graphUrl != null,
                routingProgress = routingProgress[entry.countryId],
                isRoutingReady = routingReady && graphOnDisk,
                hasUpdate = (entry.id in downloadedIds) && downloadManager.hasUpdate("region-${entry.id}", entry.dataRelease),
            )
        }
        val countryUiList = countries.map { country ->
            val graphDir = java.io.File(routingDir, "graph-${country.id}")
            val graphOnDisk = graphDir.exists() && graphDir.isDirectory && (graphDir.listFiles()?.isNotEmpty() == true)
            CountryUi(
                country = country,
                isRoutingReady = routingReady && graphOnDisk,
                routingProgress = routingProgress[country.id],
                isGeocodingReady = downloadManager.isGeocodingReady(country.id),
                geocodingProgress = geocodingProgress[country.id],
                hasRoutingUpdate = graphOnDisk && downloadManager.hasUpdate("routing-${country.id}", country.dataRelease),
                hasGeocodingUpdate = downloadManager.isGeocodingReady(country.id) && downloadManager.hasUpdate("geocoding-${country.id}", country.dataRelease),
            )
        }
        MapManagerUiState(
            isLoggedIn = isLoggedIn,
            regions = regions,
            countries = countryUiList,
            showMobileDataWarning = mobileWarning,
            errorMessage = error,
            isLoading = isLoading
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MapManagerUiState())

    fun loadCatalog() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _countries.value = catalogRepository.fetchCountries()
                _remoteRegions.value = catalogRepository.fetchRegions()
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun confirmMobileDataDownload() {
        val regionId = _showMobileDataWarning.value ?: return
        _showMobileDataWarning.value = null
        startRegionDownload(regionId)
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
            else -> startRegionDownload(regionId)
        }
    }

    private fun startRegionDownload(regionId: String) {
        val region = _remoteRegions.value.find { it.id == regionId } ?: return
        downloadManager.startDownloadFromUrl(regionId, region.name, region.mbtilesUrl, region.bbox)
    }

    fun downloadRoutingData(countryId: String) {
        val country = _countries.value.find { it.id == countryId } ?: return
        val graphUrl = country.graphUrl ?: return
        downloadManager.startRoutingDownloadFromUrl(countryId, country.name, graphUrl)
    }

    fun downloadGeocodingData(countryId: String) {
        val country = _countries.value.find { it.id == countryId } ?: return
        val geocodingUrl = country.geocodingUrl ?: return
        downloadManager.startGeocodingDownloadFromUrl(countryId, country.name, geocodingUrl)
    }

    fun clearError() {
        downloadManager.clearError()
    }

    fun deleteRegion(regionId: String) {
        viewModelScope.launch {
            val entity = db.mapRegionDao().getById(regionId) ?: return@launch
            java.io.File(entity.filePath).takeIf { it.exists() }?.delete()
            db.mapRegionDao().deleteById(regionId)
        }
    }

    fun deleteCountryData(countryId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val graphDir = java.io.File(context.filesDir, "routing/graph-$countryId")
            if (graphDir.exists()) {
                graphDir.deleteRecursively()
                routingManager.reset(graphDir)
            }
        }
    }

    fun updateRoutingData(countryId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val graphDir = java.io.File(context.filesDir, "routing/graph-$countryId")
            if (graphDir.exists()) {
                graphDir.deleteRecursively()
                routingManager.reset(graphDir)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                downloadRoutingData(countryId)
            }
        }
    }

    fun updateGeocodingData(countryId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dbFile = java.io.File(context.filesDir, "geocoding/geocoding-$countryId.db")
            if (dbFile.exists()) dbFile.delete()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                downloadGeocodingData(countryId)
            }
        }
    }

    fun updateRegion(regionId: String) {
        viewModelScope.launch {
            val entity = db.mapRegionDao().getById(regionId)
            if (entity != null) {
                java.io.File(entity.filePath).takeIf { it.exists() }?.delete()
                db.mapRegionDao().deleteById(regionId)
            }
            val region = _remoteRegions.value.find { it.id == regionId } ?: return@launch
            downloadManager.startDownloadFromUrl(regionId, region.name, region.mbtilesUrl, region.bbox)
        }
    }

    fun deleteGeocodingData(countryId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dbFile = java.io.File(context.filesDir, "geocoding/geocoding-$countryId.db")
            if (dbFile.exists()) {
                dbFile.delete()
            }
            // Trigger UI refresh
            downloadManager.geocodingProgress.value.let {
                downloadManager.notifyGeocodingChanged()
            }
        }
    }

    init {
        viewModelScope.launch {
            // Reload prebuilt routing graphs found on disk
            val routingDir = java.io.File(context.filesDir, "routing")
            if (routingDir.exists()) {
                routingDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("graph-") }?.forEach { graphDir ->
                    routingManager.loadPrebuiltGraph(graphDir)
                }
            }

            // Re-register downloaded map files missing their DB row
            val mapsDir = java.io.File(context.filesDir, "maps")
            if (mapsDir.exists()) {
                mapsDir.listFiles()?.filter { it.name.endsWith(".mbtiles") }?.forEach { mapFile ->
                    val regionId = mapFile.nameWithoutExtension
                    if (db.mapRegionDao().getById(regionId) == null) {
                        db.mapRegionDao().insert(
                            biz.cesena.packride4.data.local.MapRegion(
                                id = regionId,
                                name = regionId,
                                filePath = mapFile.absolutePath,
                                downloadedAt = mapFile.lastModified(),
                                sizeBytes = mapFile.length(),
                                bboxMinLon = 0.0, bboxMinLat = 0.0,
                                bboxMaxLon = 0.0, bboxMaxLat = 0.0
                            )
                        )
                    }
                }
            }

            // Load catalog (works offline from cache too)
            loadCatalog()
        }
    }

    private data class DownloadState(
        val downloaded: List<biz.cesena.packride4.data.local.MapRegion>,
        val progress: Map<String, Int?>,
        val routingProgress: Map<String, Int?>,
        val error: String?,
        val mobileWarning: String?
    )
}
