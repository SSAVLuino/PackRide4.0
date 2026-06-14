package biz.cesena.packride4.routing

import biz.cesena.packride4.debug.DebugLog
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
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

    /** Builds the routing graph (or loads it from cache) from [osmPbfFile]. May take a while. */
    suspend fun loadGraph(osmPbfFile: File, graphCacheDir: File) = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("routing: importing graph from ${osmPbfFile.name} (${osmPbfFile.length()}B)")
            hopper?.close()
            val gh = GraphHopper()
            gh.osmFile = osmPbfFile.absolutePath
            gh.graphHopperLocation = graphCacheDir.absolutePath
            gh.setProfiles(Profile("car").setVehicle("car").setWeighting("fastest"))
            gh.importOrLoad()
            hopper = gh
            _isReady.value = true
            DebugLog.log("routing: graph ready")
        } catch (e: Exception) {
            DebugLog.log("routing: graph load FAILED: ${e.message}")
            _isReady.value = false
        }
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
