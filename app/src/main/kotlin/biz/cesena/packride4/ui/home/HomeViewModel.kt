package biz.cesena.packride4.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class GpsPosition(val latitude: Double, val longitude: Double, val accuracy: Float)

data class HomeUiState(
    val lastKnownPosition: GpsPosition? = null,
    val hasOfflineMaps: Boolean = false,
    val isTracking: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        3_000L  // every 3 seconds while screen is on
    ).setMinUpdateDistanceMeters(5f).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                _uiState.update { state ->
                    state.copy(
                        lastKnownPosition = GpsPosition(loc.latitude, loc.longitude, loc.accuracy)
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        _uiState.update { it.copy(isTracking = true) }

        // Check whether any offline map region has been downloaded
        val mapsDir = java.io.File(context.filesDir, "maps")
        val hasMaps = mapsDir.exists() && (mapsDir.listFiles()?.any { it.extension == "mbtiles" } == true)
        _uiState.update { it.copy(hasOfflineMaps = hasMaps) }
    }

    override fun onCleared() {
        super.onCleared()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
