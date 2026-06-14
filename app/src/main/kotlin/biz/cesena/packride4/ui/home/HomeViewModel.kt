package biz.cesena.packride4.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.map.MBTilesServer
import biz.cesena.packride4.map.ShortbreadStyle
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
    val route: RouteResult? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val routingManager: RoutingManager
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
                    lastKnownPosition = GpsPosition(loc.latitude, loc.longitude, loc.accuracy)
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
    }

    /** Computes a test route from the last known GPS position to [destLat]/[destLon]. */
    fun computeTestRoute(destLat: Double, destLon: Double) {
        val from = _uiState.value.lastKnownPosition ?: return
        viewModelScope.launch {
            val result = routingManager.route(listOf(from.latitude to from.longitude, destLat to destLon))
            _uiState.update { it.copy(route = result) }
        }
    }

    fun clearRoute() {
        _uiState.update { it.copy(route = null) }
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
