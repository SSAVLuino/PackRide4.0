package biz.cesena.packride4.routing

import biz.cesena.packride4.data.download.RegionCatalogEntry
import biz.cesena.packride4.debug.DebugLog
import biz.cesena.packride4.debug.RoutingDebugDump
import org.json.JSONArray
import org.json.JSONObject
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
    val sign: Int = 0,        // GraphHopper sign: -3 sharp-left … 3 sharp-right, 4 finish, 6 roundabout
    val modifier: String = "", // OSRM modifier for roundabout/fork/ramp variants
    val exitNumber: Int = 0,  // roundabout exit number (1-8), 0 = unknown
    val speedLimitKmh: Int = 0, // max speed for this segment (0 = unknown)
    val turnAngle: Double = Double.NaN // roundabout turn angle in degrees (from GH)
)

data class RouteResult(
    val points: List<Pair<Double, Double>>, // lat, lon
    val instructions: List<RouteInstruction>,
    val distanceMeters: Double,
    val timeMillis: Long,
    val engineTag: String = "",
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
    fun loadedCount(): Int = hoppers.size

    fun canRouteLocally(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        regions: List<RegionCatalogEntry>
    ): Boolean {
        val result = regions.any { region ->
            val loaded = region.id in regionToPath
            val fromIn = region.containsPoint(fromLat, fromLon)
            val toIn = region.containsPoint(toLat, toLon)
            if (loaded) DebugLog.log("routing: canRouteLocally ${region.id}: from=$fromIn to=$toIn")
            loaded && fromIn && toIn
        }
        DebugLog.log("routing: canRouteLocally result=$result (loaded regions: ${regionToPath.keys})")
        return result
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
                req.pathDetails = listOf("max_speed")
                val rsp = gh.route(req)
                if (rsp.hasErrors()) {
                    DebugLog.log("routing: graph $key failed: ${rsp.errors.first()::class.simpleName}")
                    continue
                }
                val path = rsp.best
                val routePoints = path.points.map { it.lat to it.lon }

                // Build per-point speed limit lookup from path details
                val speedByPoint = mutableMapOf<Int, Int>()
                val rawDetails = path.pathDetails["max_speed"]
                DebugLog.log("routing: max_speed details count=${rawDetails?.size ?: 0}")
                rawDetails?.take(5)?.forEach { detail ->
                    DebugLog.log("routing: max_speed sample [${detail.first}-${detail.last}] value=${detail.value} type=${detail.value?.javaClass?.simpleName}")
                }
                rawDetails?.forEach { detail ->
                    val from = detail.first
                    val to = detail.last
                    val kmh = when (val v = detail.value) {
                        is Number -> v.toInt()
                        is String -> v.toIntOrNull() ?: 0
                        else -> 0
                    }
                    if (kmh > 0) for (i in from until to) speedByPoint[i] = kmh
                }
                DebugLog.log("routing: speedByPoint entries=${speedByPoint.size}")

                // Map instructions — RoundaboutInstruction gives exit number
                var pointIndex = 0
                val instructions = path.instructions.mapIndexed { idx, ghInstr ->
                    val isRoundabout = ghInstr is com.graphhopper.util.RoundaboutInstruction
                    val exitNum = if (isRoundabout)
                        (ghInstr as com.graphhopper.util.RoundaboutInstruction).exitNumber else 0
                    val turnAngleRad = if (isRoundabout)
                        (ghInstr as com.graphhopper.util.RoundaboutInstruction).turnAngle else Double.NaN
                    val turnAngle = if (turnAngleRad.isNaN()) Double.NaN else Math.toDegrees(turnAngleRad)
                    if (isRoundabout) {
                        DebugLog.log("routing: roundabout sign=${ghInstr.sign} exit=$exitNum angleRad=$turnAngleRad angleDeg=${"%.1f".format(turnAngle)} name=${ghInstr.name}")
                    }
                    val speed = speedByPoint[pointIndex] ?: 0
                    pointIndex += ghInstr.points.size()
                    val instrText = if (idx == 0 && ghInstr.sign == 0) "Partiamo"
                        else ghInstructionText(ghInstr.sign, ghInstr.name, exitNum)
                    RouteInstruction(
                        text = instrText,
                        distanceMeters = ghInstr.distance,
                        timeMillis = ghInstr.time,
                        sign = ghInstr.sign,
                        exitNumber = exitNum,
                        speedLimitKmh = speed,
                        turnAngle = turnAngle,
                    )
                }
                DebugLog.log("routing: route OK via $key, ${routePoints.size} pts, ${path.distance.toInt()}m")
                val destLabel = points.last().let { "${it.first},${it.second}" }
                val dumpJson = JSONObject().apply {
                    put("engine", "graphhopper")
                    put("distance", path.distance)
                    put("time", path.time)
                    put("instructions", JSONArray().also { arr ->
                        path.instructions.forEachIndexed { idx, ghInstr ->
                            val isRb = ghInstr is com.graphhopper.util.RoundaboutInstruction
                            arr.put(JSONObject().apply {
                                put("index", idx)
                                put("sign", ghInstr.sign)
                                put("name", ghInstr.name)
                                put("distance", ghInstr.distance)
                                put("time", ghInstr.time)
                                if (isRb) {
                                    val ri = ghInstr as com.graphhopper.util.RoundaboutInstruction
                                    put("exitNumber", ri.exitNumber)
                                    if (!ri.turnAngle.isNaN()) {
                                        put("turnAngleRad", ri.turnAngle)
                                        put("turnAngleDeg", Math.toDegrees(ri.turnAngle))
                                    }
                                    put("isRoundabout", true)
                                }
                            })
                        }
                    })
                }
                RoutingDebugDump.save("graphhopper", destLabel, dumpJson.toString(2))
                return@withContext RouteResult(routePoints, instructions, path.distance, path.time, "L")
            } catch (e: Exception) {
                DebugLog.log("routing: graph $key exception: ${e.message}")
            }
        }
        DebugLog.log("routing: no graph could serve the route")
        null
    }

    fun getSpeedLimit(lat: Double, lon: Double): Int {
        for ((_, gh) in hoppers) {
            try {
                val snap = gh.locationIndex.findClosest(lat, lon,
                    com.graphhopper.routing.util.EdgeFilter.ALL_EDGES)
                if (!snap.isValid) continue
                val edge = snap.closestEdge
                val maxSpeedEnc = gh.encodingManager.getDecimalEncodedValue("max_speed")
                val speed = edge.get(maxSpeedEnc)
                if (speed > 0 && speed < 999) return speed.toInt()
            } catch (_: Exception) {}
        }
        return 0
    }

    private fun ghInstructionText(sign: Int, streetName: String, exitNumber: Int): String {
        val via = if (streetName.isNotBlank()) " su $streetName" else ""
        return when (sign) {
            -7   -> "Tieniti a sinistra$via"
            -3   -> "Svolta decisamente a sinistra$via"
            -2   -> "Svolta a sinistra$via"
            -1   -> "Tieniti leggermente a sinistra$via"
            0    -> "Prosegui dritto$via"
            1    -> "Tieniti leggermente a destra$via"
            2    -> "Svolta a destra$via"
            3    -> "Svolta decisamente a destra$via"
            4    -> "Sei arrivato"
            5    -> "Sei arrivato (a destinazione intermedia)"
            6    -> if (exitNumber > 0) "Alla rotonda prendi la $exitNumber° uscita$via"
                    else "Alla rotonda$via"
            7    -> "Tieniti a destra$via"
            else -> "Prosegui$via"
        }
    }
}
