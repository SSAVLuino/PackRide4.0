package biz.cesena.packride4.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.appDataStore
import biz.cesena.packride4.map.MBTilesServer
import biz.cesena.packride4.map.ShortbreadStyle
import biz.cesena.packride4.ui.settings.MapSourceMode
import com.google.android.gms.location.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import androidx.datastore.preferences.core.stringPreferencesKey

data class GpsPosition(val latitude: Double, val longitude: Double, val accuracy: Float)

data class HomeUiState(
    val lastKnownPosition: GpsPosition? = null,
    val hasOfflineMaps: Boolean = false,
    val isTracking: Boolean = false,
    val mapStyleJson: String = ShortbreadStyle.online,
    val mapSourceMode: MapSourceMode = MapSourceMode.AUTO
)

private val MAP_SOURCE_KEY = stringPreferencesKey("map_source_mode")

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase
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
    }

    private fun startMBTilesServer() {
        runCatching { mbTilesServer.start() }
    }

    private fun observeMapSource() {
        viewModelScope.launch {
            combine(
                context.appDataStore.data.map { prefs ->
                    runCatching { MapSourceMode.valueOf(prefs[MAP_SOURCE_KEY] ?: "AUTO") }
                        .getOrDefault(MapSourceMode.AUTO)
                },
                db.mapRegionDao().getAll()
            ) { mode, downloadedRegions ->
                val mapFiles = downloadedRegions
                    .map { File(it.filePath) }
                    .filter { it.exists() }
                val hasOffline = mapFiles.isNotEmpty()

                val useOffline = when (mode) {
                    MapSourceMode.OFFLINE -> true
                    MapSourceMode.ONLINE -> false
                    MapSourceMode.AUTO -> hasOffline
                }

                if (useOffline) {
                    mbTilesServer.loadMaps(mapFiles)
                } else {
                    mbTilesServer.loadMaps(emptyList())
                }

                Triple(mode, hasOffline, useOffline)
            }.collect { (mode, hasOffline, useOffline) ->
                _uiState.update { it.copy(
                    hasOfflineMaps = hasOffline,
                    mapSourceMode = mode,
                    mapStyleJson = if (useOffline) ShortbreadStyle.offline() else ShortbreadStyle.online
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

    override fun onCleared() {
        super.onCleared()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        mbTilesServer.stop()
    }
}
