package biz.cesena.packride4.routing

import biz.cesena.packride4.BuildConfig
import biz.cesena.packride4.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnlineRoutingService @Inject constructor() {

    private val tomTomKey: String = BuildConfig.TOMTOM_API_KEY

    suspend fun route(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): RouteResult? = routeMulti(listOf(fromLat to fromLon, toLat to toLon))

    suspend fun routeMulti(
        waypoints: List<Pair<Double, Double>>
    ): RouteResult? = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext null
        if (tomTomKey.isNotEmpty()) {
            val tomTomResult = routeViaTomTom(waypoints)
            if (tomTomResult != null) {
                DebugLog.log("online-routing: TomTom OK, ${tomTomResult.distanceMeters.toInt()}m")
                return@withContext tomTomResult
            }
            DebugLog.log("online-routing: TomTom failed, trying OSRM")
        }
        val osrmResult = routeViaOsrm(waypoints)
        if (osrmResult != null) {
            DebugLog.log("online-routing: OSRM OK, ${osrmResult.distanceMeters.toInt()}m")
        } else {
            DebugLog.log("online-routing: OSRM also failed")
        }
        osrmResult
    }

    private fun routeViaTomTom(waypoints: List<Pair<Double, Double>>): RouteResult? {
        return try {
            val coordsPath = waypoints.joinToString(":") { (lat, lon) -> "$lat,$lon" }
            val url = "https://api.tomtom.com/routing/1/calculateRoute/" +
                    "$coordsPath/json" +
                    "?key=$tomTomKey&traffic=false&travelMode=car&instructionsType=coded&language=it-IT"
            val json = JSONObject(fetchJson(url) ?: return null)
            val route = json.getJSONArray("routes").getJSONObject(0)
            val summary = route.getJSONObject("summary")
            val distanceM = summary.getDouble("lengthInMeters")
            val timeMs = summary.getLong("travelTimeInSeconds") * 1000L

            val points = mutableListOf<Pair<Double, Double>>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val pts = legs.getJSONObject(i).getJSONArray("points")
                for (j in 0 until pts.length()) {
                    val p = pts.getJSONObject(j)
                    points += p.getDouble("latitude") to p.getDouble("longitude")
                }
            }

            val instructions = mutableListOf<RouteInstruction>()
            val guidance = route.optJSONObject("guidance")
            val rawInstr = guidance?.optJSONArray("instructions")
            if (rawInstr != null) {
                for (i in 0 until rawInstr.length()) {
                    val instr = rawInstr.getJSONObject(i)
                    val maneuver = instr.optString("maneuver", "STRAIGHT")
                    val sign = tomTomManeuverToSign(maneuver)
                    val text = instr.optString("message", "").ifBlank { maneuverToText(maneuver) }
                    val distM = instr.optDouble("routeOffsetInMeters", 0.0)
                    val timeS = instr.optLong("travelTimeInSeconds", 0L)
                    val modifier = if (sign == 6) tomTomRoundaboutModifier(maneuver) else ""
                    val exitNum = if (sign == 6) instr.optInt("roundaboutExitNumber", 0) else 0
                    instructions += RouteInstruction(text, distM, timeS * 1000L, sign, modifier, exitNum)
                }
            }
            DebugLog.log("online-routing: TomTom ${instructions.size} istruzioni")
            RouteResult(points, instructions, distanceM, timeMs)
        } catch (e: Exception) {
            DebugLog.log("online-routing: TomTom exception: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun routeViaOsrm(waypoints: List<Pair<Double, Double>>): RouteResult? {
        return try {
            val coordsStr = waypoints.joinToString(";") { (lat, lon) -> "$lon,$lat" }
            val url = "http://router.project-osrm.org/route/v1/driving/" +
                    "$coordsStr?overview=full&geometries=geojson&steps=true"
            val json = JSONObject(fetchJson(url) ?: return null)
            if (json.getString("code") != "Ok") return null
            val route = json.getJSONArray("routes").getJSONObject(0)
            val distanceM = route.getDouble("distance")
            val timeMs = (route.getDouble("duration") * 1000).toLong()
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until coords.length()).map { i ->
                val c = coords.getJSONArray(i)
                c.getDouble(1) to c.getDouble(0)
            }

            val instructions = mutableListOf<RouteInstruction>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val steps = legs.getJSONObject(i).optJSONArray("steps") ?: continue
                for (j in 0 until steps.length()) {
                    val step = steps.getJSONObject(j)
                    val maneuver = step.getJSONObject("maneuver")
                    val type = maneuver.optString("type", "")
                    val mod = maneuver.optString("modifier", "straight")
                    val sign = osrmToSign(type, mod)
                    val streetName = step.optString("name", "").ifBlank { "" }
                    val text = osrmStepText(type, mod, streetName)
                    val stepDist = step.optDouble("distance", 0.0)
                    val stepTime = (step.optDouble("duration", 0.0) * 1000).toLong()
                    val exitNum = if (sign == 6) maneuver.optInt("exit", 0) else 0
                    instructions += RouteInstruction(text, stepDist, stepTime, sign, mod, exitNum)
                }
            }
            DebugLog.log("online-routing: OSRM ${instructions.size} istruzioni")
            RouteResult(points, instructions, distanceM, timeMs)
        } catch (e: Exception) {
            DebugLog.log("online-routing: OSRM exception: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    // ── TomTom maneuver helpers ───────────────────────────────────────────────

    private fun tomTomManeuverToSign(maneuver: String): Int = when {
        maneuver == "TURN_SHARP_LEFT"  -> -3
        maneuver == "TURN_LEFT"        -> -2
        maneuver == "TURN_SLIGHT_LEFT" -> -1
        maneuver == "TURN_SLIGHT_RIGHT"-> 1
        maneuver == "TURN_RIGHT"       -> 2
        maneuver == "TURN_SHARP_RIGHT" -> 3
        maneuver in listOf("ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT") -> 4
        maneuver.startsWith("ROUNDABOUT") -> 6
        else               -> 0
    }

    private fun tomTomRoundaboutModifier(maneuver: String): String = when (maneuver) {
        "ROUNDABOUT_SHARP_LEFT"  -> "sharp left"
        "ROUNDABOUT_LEFT"        -> "left"
        "ROUNDABOUT_SLIGHT_LEFT" -> "slight left"
        "ROUNDABOUT_STRAIGHT"    -> "straight"
        "ROUNDABOUT_SLIGHT_RIGHT"-> "slight right"
        "ROUNDABOUT_RIGHT"       -> "right"
        "ROUNDABOUT_SHARP_RIGHT" -> "sharp right"
        else                     -> ""
    }

    private fun maneuverToText(maneuver: String): String = when (maneuver) {
        "TURN_SHARP_LEFT"  -> "Svolta decisamente a sinistra"
        "TURN_LEFT"        -> "Svolta a sinistra"
        "TURN_SLIGHT_LEFT" -> "Tieniti a sinistra"
        "TURN_SLIGHT_RIGHT"-> "Tieniti a destra"
        "TURN_RIGHT"       -> "Svolta a destra"
        "TURN_SHARP_RIGHT" -> "Svolta decisamente a destra"
        "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT" -> "Sei arrivato"
        "STRAIGHT"         -> "Prosegui dritto"
        "LOCATION_DEPARTURE" -> "Parti"
        else               -> maneuver.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    // ── OSRM step helpers ─────────────────────────────────────────────────────

    private fun osrmToSign(type: String, modifier: String): Int = when {
        type == "arrive"                        -> 4
        type == "depart"                        -> 0
        type == "roundabout" || type == "rotary" -> 6
        modifier == "sharp left"                -> -3
        modifier == "left"                      -> -2
        modifier == "slight left"               -> -1
        modifier == "slight right"              -> 1
        modifier == "right"                     -> 2
        modifier == "sharp right"               -> 3
        else                                    -> 0
    }

    private fun osrmStepText(type: String, modifier: String, street: String): String {
        val via = if (street.isNotBlank()) " su $street" else ""
        return when {
            type == "arrive"        -> "Sei arrivato"
            type == "depart"        -> "Parti$via"
            modifier == "sharp left"  -> "Svolta decisamente a sinistra$via"
            modifier == "left"        -> "Svolta a sinistra$via"
            modifier == "slight left" -> "Tieniti a sinistra$via"
            modifier == "straight"    -> "Prosegui dritto$via"
            modifier == "slight right"-> "Tieniti a destra$via"
            modifier == "right"       -> "Svolta a destra$via"
            modifier == "sharp right" -> "Svolta decisamente a destra$via"
            else                      -> "Prosegui$via"
        }
    }

    private fun fetchJson(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                DebugLog.log("online-routing: HTTP ${conn.responseCode} for $urlString")
                return null
            }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            DebugLog.log("online-routing: network error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
