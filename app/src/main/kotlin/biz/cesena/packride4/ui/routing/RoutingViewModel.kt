package biz.cesena.packride4.ui.routing

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.service.NavigationService
import biz.cesena.packride4.utils.ConnectivityUtils
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.Parameters
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject

data class RouteSummary(val distanceKm: String, val eta: String)

data class RoutingUiState(
    val destinationQuery: String = "",
    val isCalculating: Boolean = false,
    val isNavigating: Boolean = false,
    val routeSummary: RouteSummary? = null,
    val errorMessage: String? = null
)

@Serializable
private data class NominatimResult(val lat: String, val lon: String)

@HiltViewModel
class RoutingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutingUiState())
    val uiState: StateFlow<RoutingUiState> = _uiState.asStateFlow()

    fun onDestinationQueryChange(value: String) =
        _uiState.update { it.copy(destinationQuery = value, errorMessage = null, routeSummary = null) }

    /**
     * Risolve la destinazione: se è "lat,lon" la usa direttamente, altrimenti
     * cerca l'indirizzo/luogo online (Nominatim) — richiede connessione.
     */
    fun searchDestination() {
        val query = _uiState.value.destinationQuery.trim()
        if (query.isEmpty()) return

        val coords = query.split(",").mapNotNull { it.trim().toDoubleOrNull() }

        viewModelScope.launch {
            _uiState.update { it.copy(isCalculating = true, errorMessage = null) }
            try {
                val (destLat, destLon) = if (coords.size == 2) {
                    coords[0] to coords[1]
                } else {
                    if (!ConnectivityUtils.isConnected(context)) {
                        _uiState.update { it.copy(errorMessage = "Ricerca indirizzo non disponibile offline — usa coordinate (lat, lon) o connettiti a internet") }
                        return@launch
                    }
                    val result = geocode(query)
                        ?: run {
                            _uiState.update { it.copy(errorMessage = "Nessun risultato trovato per \"$query\"") }
                            return@launch
                        }
                    result
                }

                val summary = withContext(Dispatchers.IO) {
                    calculateRoute(destLat, destLon)
                }
                _uiState.update { it.copy(routeSummary = summary) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Errore nel calcolo del percorso: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isCalculating = false) }
            }
        }
    }

    private suspend fun geocode(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val results: List<NominatimResult> = httpClient.get("https://nominatim.openstreetmap.org/search") {
            header("User-Agent", "PackRide4/4.0 (biz.cesena.packride4)")
            url {
                parameters.append("format", "json")
                parameters.append("limit", "1")
                parameters.append("q", query)
            }
        }.body()
        results.firstOrNull()?.let { it.lat.toDoubleOrNull()?.let { lat -> lat to (it.lon.toDoubleOrNull() ?: return@let null) } }
    }

    private fun calculateRoute(destLat: Double, destLon: Double): RouteSummary {
        val graphDir = File(context.filesDir, "graphhopper")
        val osmFile = File(context.filesDir, "maps/current.osm.pbf")

        if (!graphDir.exists() || osmFile.exists()) {
            // First run or new OSM file: import the data
            graphDir.mkdirs()
        }

        val hopper = GraphHopper().apply {
            osmFile.takeIf { it.exists() }?.let { setOSMFile(it.absolutePath) }
            graphHopperLocation = graphDir.absolutePath
            setProfiles(Profile("car"))
            chPreparationHandler.setCHProfiles(CHProfile("car"))
        }
        hopper.importOrLoad()

        // Use a fixed "current position" placeholder — replace with real GPS lat/lon
        val startLat = 44.1391  // Cesena, IT
        val startLon = 12.2435

        val req = GHRequest(startLat, startLon, destLat, destLon).apply {
            profile = "car"
            putHint(Parameters.Routing.INSTRUCTIONS, true)
        }
        val resp: GHResponse = hopper.route(req)
        if (resp.hasErrors()) throw Exception(resp.errors.first().message)

        val path = resp.best
        val distKm = path.distance / 1000.0
        val etaMin = (path.time / 60_000).toInt()

        return RouteSummary(
            distanceKm = "%.1f km".format(distKm),
            eta = if (etaMin >= 60) "%dh %02dmin".format(etaMin / 60, etaMin % 60) else "$etaMin min"
        )
    }

    fun startNavigation() {
        val intent = Intent(context, NavigationService::class.java).apply {
            action = NavigationService.ACTION_START
        }
        context.startForegroundService(intent)
        _uiState.update { it.copy(isNavigating = true) }
    }

    fun stopNavigation() {
        val intent = Intent(context, NavigationService::class.java).apply {
            action = NavigationService.ACTION_STOP
        }
        context.startService(intent)
        _uiState.update { it.copy(isNavigating = false) }
    }
}
