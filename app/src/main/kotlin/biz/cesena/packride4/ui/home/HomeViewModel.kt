package biz.cesena.packride4.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.download.AVAILABLE_REGIONS
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.debug.DebugLog
import biz.cesena.packride4.map.MBTilesServer
import biz.cesena.packride4.map.ShortbreadStyle
import biz.cesena.packride4.routing.OnlineRoutingService
import biz.cesena.packride4.routing.RouteResult
import biz.cesena.packride4.routing.RoutingManager
import com.google.android.gms.location.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val routingManager: RoutingManager,
    private val onlineRoutingService: OnlineRoutingService
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val mbTilesServer = MBTilesServer(port = 8787)

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
        // Load any prebuilt routing graphs found on disk so isRoutingReady is set at startup.
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

    /** Computes a route. If fromLat/fromLon are provided uses those, otherwise uses last GPS position. */
    fun computeTestRoute(destLat: Double, destLon: Double, fromLat: Double? = null, fromLon: Double? = null) {
        val (oLat, oLon) = if (fromLat != null && fromLon != null) fromLat to fromLon
                           else _uiState.value.lastKnownPosition?.let { it.latitude to it.longitude } ?: return
        viewModelScope.launch {
            val result = if (routingManager.canRouteLocally(oLat, oLon, destLat, destLon, AVAILABLE_REGIONS)) {
                DebugLog.log("routing: local graph covers both endpoints — using GraphHopper")
                routingManager.route(listOf(oLat to oLon, destLat to destLon))
            } else {
                DebugLog.log("routing: cross-region or no local graph — using online routing")
                onlineRoutingService.route(oLat, oLon, destLat, destLon)
            }
            _uiState.update { it.copy(route = result) }
        }
    }

    fun clearRoute() {
        _uiState.update { it.copy(route = null, isNavigating = false, currentInstructionIndex = 0) }
    }

    fun startNavigation() {
        _uiState.update { it.copy(isNavigating = true, isFollowing = true, currentInstructionIndex = 0) }
    }

    fun stopNavigation() {
        _uiState.update { it.copy(isNavigating = false, currentInstructionIndex = 0, route = null) }
    }

    private fun startMBTilesServer() {
        runCatching { mbTilesServer.start() }
    }

    private fun observeMapSource() {
        viewModelScope.launch {
            db.mapRegionDao().getAll().collect { downloadedRegions ->
                val mapFiles = downloadedRegions
                    .map { File(it.filePath) }
                    .filter { it.exists() }
                val hasOffline = mapFiles.isNotEmpty()

                mbTilesServer.loadMaps(mapFiles)

                _uiState.update { it.copy(
                    hasOfflineMaps = hasOffline,
                    mapStyleJson = if (hasOffline) ShortbreadStyle.offline() else ShortbreadStyle.online
                )}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
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
