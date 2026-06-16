package biz.cesena.packride4.routing

import biz.cesena.packride4.debug.DebugLog
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class RouteInstruction(
    val text: String,
    val distanceMeters: Double,
    val timeMillis: Long
)

data class RouteResult(
    val points: List<Pair<Double, Double>>, // lat, lon
    val instructions: List<RouteInstruction>,
    val distanceMeters: Double,
    val timeMillis: Long
)

/** Builds/loads an on-device GraphHopper routing graph from an OSM pbf and computes routes. */
@Singleton
class RoutingManager @Inject constructor() {

    private var hopper: GraphHopper? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private fun carProfile(): Profile {
        return Profile("car").setVehicle("car").setWeighting("fastest")
    }

    /** Loads a prebuilt routing graph from [graphDir] (downloaded as a ready-made archive). */
    suspend fun loadPrebuiltGraph(graphDir: File) = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("routing: loading prebuilt graph from ${graphDir.absolutePath}")
            hopper?.close()
            val config = GraphHopperConfig()
            config.putObject("graph.dataaccess", "MMAP")
            config.putObject("graph.location", graphDir.absolutePath)
config.setProfiles(listOf(carProfile()))
            val gh = GraphHopper().init(config)
            if (!gh.load()) {
                DebugLog.log("routing: prebuilt graph load FAILED: gh.load() returned false")
                _isReady.value = false
                return@withContext
            }
            hopper = gh
            _isReady.value = true
            DebugLog.log("routing: graph ready")
        } catch (e: Throwable) {
            android.util.Log.e("PackRideDebug", "routing: graph load FAILED", e)
            DebugLog.log("routing: graph load FAILED: ${e::class.simpleName}: ${e.message}")
            _isReady.value = false
        }
    }

    /** Unloads the current graph (if any) and marks routing as not ready. */
    suspend fun reset() = withContext(Dispatchers.IO) {
        hopper?.close()
        hopper = null
        _isReady.value = false
    }

    suspend fun route(points: List<Pair<Double, Double>>): RouteResult? = withContext(Dispatchers.IO) {
        val gh = hopper ?: run {
            DebugLog.log("routing: no graph loaded")
            return@withContext null
        }
        try {
            val req = GHRequest()
            points.forEach { (lat, lon) -> req.addPoint(com.graphhopper.util.shapes.GHPoint(lat, lon)) }
            req.profile = "car"
            val rsp = gh.route(req)
            if (rsp.hasErrors()) {
                DebugLog.log("routing: error: ${rsp.errors}")
                return@withContext null
            }
            val path = rsp.best
            val routePoints = path.points.map { it.lat to it.lon }
            val instructions = path.instructions.map {
                RouteInstruction(it.name, it.distance, it.time)
            }
            DebugLog.log("routing: route OK, ${routePoints.size} pts, ${path.distance.toInt()}m, ${instructions.size} instructions")
            RouteResult(routePoints, instructions, path.distance, path.time)
        } catch (e: Exception) {
            DebugLog.log("routing: route FAILED: ${e.message}")
            null
        }
    }
}
