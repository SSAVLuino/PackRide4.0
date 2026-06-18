package biz.cesena.packride4.routing

import biz.cesena.packride4.data.download.RegionCatalogEntry
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
    val timeMillis: Long,
    val sign: Int = 0   // GraphHopper sign: -3 sharp-left … 3 sharp-right, 4 finish
)

data class RouteResult(
    val points: List<Pair<Double, Double>>, // lat, lon
    val instructions: List<RouteInstruction>,
    val distanceMeters: Double,
    val timeMillis: Long
)

/** Manages one or more on-device GraphHopper routing graphs and computes routes. */
@Singleton
class RoutingManager @Inject constructor() {

    // graphDir.absolutePath -> GraphHopper instance
    private val hoppers = mutableMapOf<String, GraphHopper>()
    // regionId -> graphDir.absolutePath (to support canRouteLocally checks)
    private val regionToPath = mutableMapOf<String, String>()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private fun carProfile(): Profile {
        return Profile("car").setVehicle("car").setWeighting("fastest")
    }

    /** Loads a prebuilt routing graph from [graphDir]. Multiple graphs can coexist. */
    suspend fun loadPrebuiltGraph(graphDir: File, regionId: String = graphDir.name) = withContext(Dispatchers.IO) {
        val key = graphDir.absolutePath
        if (hoppers.containsKey(key)) {
            DebugLog.log("routing: graph already loaded for $key, skipping")
            return@withContext
        }
        try {
            DebugLog.log("routing: loading prebuilt graph from $key (region=$regionId)")
            val config = GraphHopperConfig()
            config.putObject("graph.dataaccess", "MMAP")
            config.putObject("graph.location", key)
            config.setProfiles(listOf(carProfile()))
            val gh = GraphHopper().init(config)
            if (!gh.load()) {
                DebugLog.log("routing: prebuilt graph load FAILED: gh.load() returned false")
                return@withContext
            }
            hoppers[key] = gh
            regionToPath[regionId] = key
            _isReady.value = true
            DebugLog.log("routing: graph ready (${hoppers.size} total)")
        } catch (e: Throwable) {
            android.util.Log.e("PackRideDebug", "routing: graph load FAILED", e)
            DebugLog.log("routing: graph load FAILED: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** Unloads the graph at [graphDir] (or all graphs if null). */
    suspend fun reset(graphDir: File? = null) = withContext(Dispatchers.IO) {
        if (graphDir == null) {
            hoppers.values.forEach { it.close() }
            hoppers.clear()
            regionToPath.clear()
        } else {
            val key = graphDir.absolutePath
            hoppers.remove(key)?.close()
            regionToPath.entries.removeIf { it.value == key }
        }
        _isReady.value = hoppers.isNotEmpty()
    }

    /**
     * Returns true if both [from] and [to] fall inside the bounding box of a single
     * loaded region — meaning GraphHopper can serve the route entirely offline.
     */
    fun canRouteLocally(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        regions: List<RegionCatalogEntry>
    ): Boolean = regions.any { region ->
        region.id in regionToPath &&
        region.containsPoint(fromLat, fromLon) &&
        region.containsPoint(toLat, toLon)
    }

    suspend fun route(points: List<Pair<Double, Double>>): RouteResult? = withContext(Dispatchers.IO) {
        if (hoppers.isEmpty()) {
            DebugLog.log("routing: no graph loaded")
            return@withContext null
        }
        DebugLog.log("routing: request from ${points.first()} to ${points.last()} (${hoppers.size} graphs)")
        // Try each loaded graph; use the first one that returns a valid route
        for ((key, gh) in hoppers) {
            try {
                val req = GHRequest()
                points.forEach { (lat, lon) -> req.addPoint(com.graphhopper.util.shapes.GHPoint(lat, lon)) }
                req.profile = "car"
                val rsp = gh.route(req)
                if (rsp.hasErrors()) {
                    DebugLog.log("routing: graph $key failed: ${rsp.errors.first()::class.simpleName}")
                    continue
                }
                val path = rsp.best
                val routePoints = path.points.map { it.lat to it.lon }
                val instructions = path.instructions.map {
                    RouteInstruction(it.name, it.distance, it.time, it.sign)
                }
                DebugLog.log("routing: route OK via $key, ${routePoints.size} pts, ${path.distance.toInt()}m")
                return@withContext RouteResult(routePoints, instructions, path.distance, path.time)
            } catch (e: Exception) {
                DebugLog.log("routing: graph $key exception: ${e.message}")
            }
        }
        DebugLog.log("routing: no graph could serve the route")
        null
    }
}
