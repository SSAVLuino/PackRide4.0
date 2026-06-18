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

/**
 * Online routing: tries TomTom first (if API key configured), falls back to OSRM public demo.
 *
 * TomTom key → set TOMTOM_API_KEY in local.properties.
 * OSRM is rate-limited and for development/fallback only; replace with a self-hosted instance
 * or another provider for production.
 */
@Singleton
class OnlineRoutingService @Inject constructor() {

    private val tomTomKey: String = BuildConfig.TOMTOM_API_KEY

    suspend fun route(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): RouteResult? = withContext(Dispatchers.IO) {
        if (tomTomKey.isNotEmpty()) {
            val tomTomResult = routeViaTomTom(fromLat, fromLon, toLat, toLon)
            if (tomTomResult != null) {
                DebugLog.log("online-routing: TomTom OK, ${tomTomResult.distanceMeters.toInt()}m")
                return@withContext tomTomResult
            }
            DebugLog.log("online-routing: TomTom failed, trying OSRM")
        }
        val osrmResult = routeViaOsrm(fromLat, fromLon, toLat, toLon)
        if (osrmResult != null) {
            DebugLog.log("online-routing: OSRM OK, ${osrmResult.distanceMeters.toInt()}m")
        } else {
            DebugLog.log("online-routing: OSRM also failed")
        }
        osrmResult
    }

    private fun routeViaTomTom(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): RouteResult? {
        return try {
            val url = "https://api.tomtom.com/routing/1/calculateRoute/" +
                    "$fromLat,$fromLon:$toLat,$toLon/json?key=$tomTomKey&traffic=false&travelMode=car"
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
            RouteResult(points, emptyList(), distanceM, timeMs)
        } catch (e: Exception) {
            DebugLog.log("online-routing: TomTom exception: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun routeViaOsrm(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): RouteResult? {
        return try {
            val url = "http://router.project-osrm.org/route/v1/driving/" +
                    "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
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
            RouteResult(points, emptyList(), distanceM, timeMs)
        } catch (e: Exception) {
            DebugLog.log("online-routing: OSRM exception: ${e::class.simpleName}: ${e.message}")
            null
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
