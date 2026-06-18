package biz.cesena.packride4.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.download.AVAILABLE_REGIONS
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.SavedRoute
import biz.cesena.packride4.data.local.SavedRouteDao
import biz.cesena.packride4.data.prefs.UserPreferences
import biz.cesena.packride4.debug.DebugLog
import biz.cesena.packride4.map.MBTilesServer
import biz.cesena.packride4.map.ShortbreadStyle
import biz.cesena.packride4.routing.GeocodingResult
import biz.cesena.packride4.routing.GeocodingService
import biz.cesena.packride4.routing.OnlineRoutingService
import biz.cesena.packride4.routing.RouteResult
import biz.cesena.packride4.routing.RoutingManager
import com.google.android.gms.location.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class GpsPosition(val latitude: Double, val longitude: Double, val accuracy: Float)

data class HomeUiState(
    val lastKnownPosition: GpsPosition? = null,
    val hasOfflineMaps: Boolean = false,
    val isTracking: Boolean = false,
    val isFollowing: Boolean = false,
    val mapStyleJson: String = ShortbreadStyle.online,
    val isRoutingReady: Boolean = false,
    val route: RouteResult? = null,
    // Navigation state
    val isNavigating: Boolean = false,
    val currentInstructionIndex: Int = 0,
    val speedKmh: Float = 0f,
    // Search state
    val searchQuery: String = "",
    val searchResults: List<GeocodingResult> = emptyList(),
    val isSearchLoading: Boolean = false,
    // Destination info (used when saving)
    val destinationName: String = "",
    val destinationLat: Double = 0.0,
    val destinationLon: Double = 0.0,
    val routeSaved: Boolean = false,
    // Routing error feedback
    val routeError: String? = null,
    val isRouteCalculating: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val savedRouteDao: SavedRouteDao,
    private val routingManager: RoutingManager,
    private val onlineRoutingService: OnlineRoutingService,
    private val geocodingService: GeocodingService,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val mbTilesServer = MBTilesServer(port = 8787)
    private var searchJob: Job? = null

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 3_000L
    ).setMinUpdateDistanceMeters(5f).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                _uiState.update { it.copy(
                    lastKnownPosition = GpsPosition(loc.latitude, loc.longitude, loc.accuracy),
                    speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else it.speedKmh
                )}
            }
        }
    }

    init {
        startMBTilesServer()
        observeMapSource()
        viewModelScope.launch {
            routingManager.isReady.collect { ready ->
                _uiState.update { it.copy(isRoutingReady = ready) }
            }
        }
        viewModelScope.launch {
            val routingDir = File(context.filesDir, "routing")
            for (entry in AVAILABLE_REGIONS) {
                val graphDir = File(routingDir, "graph-${entry.id}")
                if (graphDir.exists() && graphDir.isDirectory) {
                    routingManager.loadPrebuiltGraph(graphDir, entry.id)
                }
            }
        }
    }

    // ── Search / geocoding ──────────────────────────────────────────────────

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearchLoading = false) }
            return
        }
        _uiState.update { it.copy(isSearchLoading = true) }
        searchJob = viewModelScope.launch {
            delay(300) // debounce
            val results = geocodingService.search(query)
            _uiState.update { it.copy(searchResults = results, isSearchLoading = false) }
        }
    }

    fun selectSearchResult(result: GeocodingResult) {
        _uiState.update { it.copy(
            searchQuery = "", searchResults = emptyList(), isSearchLoading = false,
            destinationName = result.name,
            destinationLat = result.lat,
            destinationLon = result.lon,
            routeSaved = false,
            routeError = null,
        )}
        computeRoute(result.lat, result.lon)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearchLoading = false) }
    }

    // ── Routing ─────────────────────────────────────────────────────────────

    fun computeRoute(destLat: Double, destLon: Double) {
        val pos = _uiState.value.lastKnownPosition
        if (pos == null) {
            _uiState.update { it.copy(routeError = "Posizione GPS non disponibile") }
            return
        }
        val (oLat, oLon) = pos.latitude to pos.longitude
        _uiState.update { it.copy(isRouteCalculating = true, routeError = null) }
        viewModelScope.launch {
            val result = if (routingManager.canRouteLocally(oLat, oLon, destLat, destLon, AVAILABLE_REGIONS)) {
                DebugLog.log("routing: trying local GraphHopper")
                val local = routingManager.route(listOf(oLat to oLon, destLat to destLon))
                if (local != null) {
                    DebugLog.log("routing: local OK")
                    local
                } else {
                    // Local graph exists but couldn't route (e.g. destination just outside coverage)
                    DebugLog.log("routing: local failed, falling back to online")
                    onlineRoutingService.route(oLat, oLon, destLat, destLon)
                }
            } else {
                DebugLog.log("routing: online (TomTom/OSRM)")
                onlineRoutingService.route(oLat, oLon, destLat, destLon)
            }
            _uiState.update { it.copy(
                route = result,
                isRouteCalculating = false,
                routeError = if (result == null) "Impossibile calcolare il percorso" else null
            )}
        }
    }

    fun saveRoute() {
        val state = _uiState.value
        val route = state.route ?: return
        viewModelScope.launch {
            savedRouteDao.insert(SavedRoute(
                name = state.destinationName.ifBlank { "Percorso" },
                destinationLat = state.destinationLat,
                destinationLon = state.destinationLon,
                distanceMeters = route.distanceMeters,
                durationMillis = route.timeMillis,
                pointsJson = SavedRoute.serializePoints(route.points),
                instructionsJson = SavedRoute.serializeInstructions(route.instructions),
            ))
            _uiState.update { it.copy(routeSaved = true) }
        }
    }

    fun clearRoute() {
        _uiState.update { it.copy(route = null, isNavigating = false, currentInstructionIndex = 0, routeSaved = false) }
    }

    fun startNavigation() {
        _uiState.update { it.copy(isNavigating = true, isFollowing = true, currentInstructionIndex = 0) }
    }

    fun stopNavigation() {
        _uiState.update { it.copy(isNavigating = false, currentInstructionIndex = 0, route = null) }
    }

    // ── Map / location ──────────────────────────────────────────────────────

    private fun startMBTilesServer() {
        runCatching { mbTilesServer.start() }
    }

    private fun observeMapSource() {
        viewModelScope.launch {
            combine(
                db.mapRegionDao().getAll(),
                userPreferences.useOfflineMap
            ) { downloadedRegions, useOffline ->
                downloadedRegions to useOffline
            }.collect { (downloadedRegions, useOffline) ->
                val mapFiles = downloadedRegions.map { File(it.filePath) }.filter { it.exists() }
                val hasOffline = mapFiles.isNotEmpty()
                if (useOffline && hasOffline) {
                    mbTilesServer.loadMaps(mapFiles)
                } else {
                    mbTilesServer.loadMaps(emptyList())
                }
                _uiState.update { it.copy(
                    hasOfflineMaps = hasOffline,
                    mapStyleJson = if (useOffline && hasOffline) ShortbreadStyle.offline() else ShortbreadStyle.online
                )}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        _uiState.update { it.copy(isTracking = true) }
    }

    fun toggleFollow() {
        _uiState.update { it.copy(isFollowing = !it.isFollowing) }
    }

    override fun onCleared() {
        super.onCleared()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        mbTilesServer.stop()
    }
}
