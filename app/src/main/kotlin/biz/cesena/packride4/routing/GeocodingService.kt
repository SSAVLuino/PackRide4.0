package biz.cesena.packride4.routing

import biz.cesena.packride4.BuildConfig
import biz.cesena.packride4.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class GeocodingResult(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double = 0.0,
)

@Singleton
class GeocodingService @Inject constructor(
    private val offlineGeocoding: OfflineGeocodingService
) {

    private val apiKey = BuildConfig.TOMTOM_API_KEY

    suspend fun search(query: String, userLat: Double = 0.0, userLon: Double = 0.0): List<GeocodingResult> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()

        offlineGeocoding.cancelPending()
        if (offlineGeocoding.isAvailable()) {
            offlineGeocoding.userLat = userLat
            offlineGeocoding.userLon = userLon
            val offlineResults = offlineGeocoding.search(query)
            if (offlineResults.isNotEmpty()) return@withContext offlineResults
        }

        if (apiKey.isEmpty()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.tomtom.com/search/2/search/$encoded.json" +
                    "?key=$apiKey&limit=6&countrySet=IT,CH&language=it-IT"
            val json = JSONObject(fetchJson(url) ?: return@withContext emptyList())
            val results = json.getJSONArray("results")
            (0 until results.length()).mapNotNull { i ->
                val r = results.getJSONObject(i)
                val pos = r.optJSONObject("position") ?: return@mapNotNull null
                val addr = r.optJSONObject("address")
                val poi = r.optJSONObject("poi")
                val name = poi?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: addr?.optString("municipality")
                    ?: addr?.optString("freeformAddress")
                    ?: ""
                val address = addr?.optString("freeformAddress") ?: ""
                val lat = pos.getDouble("lat")
                val lon = pos.getDouble("lon")
                val distKm = if (userLat != 0.0 || userLon != 0.0) haversine(userLat, userLon, lat, lon) / 1000.0 else 0.0
                GeocodingResult(name, address, lat, lon, distKm)
            }.sortedBy { it.distanceKm }
            .also { DebugLog.log("geocoding: ${it.size} results for \"$query\"") }
        } catch (e: Exception) {
            DebugLog.log("geocoding error: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun fetchJson(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                DebugLog.log("geocoding: HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            DebugLog.log("geocoding: network error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
