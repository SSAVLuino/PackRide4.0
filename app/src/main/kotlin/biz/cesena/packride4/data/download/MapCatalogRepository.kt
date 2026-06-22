package biz.cesena.packride4.data.download

import biz.cesena.packride4.BuildConfig
import biz.cesena.packride4.data.auth.AuthRepository
import biz.cesena.packride4.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class MapCountry(
    val id: String,
    val name: String,
    val bbox: String,
    val graphUrl: String? = null,
    val geocodingUrl: String? = null,
    val graphSizeMb: Double = 0.0,
    val geocodingSizeMb: Double = 0.0
)

data class MapRegionRemote(
    val id: String,
    val countryId: String,
    val name: String,
    val mbtilesUrl: String,
    val mbtilesSizeMb: Double = 0.0,
    val bbox: String
)

@Singleton
class MapCatalogRepository @Inject constructor(
    private val authRepository: AuthRepository
) {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    suspend fun fetchCountries(): List<MapCountry> = withContext(Dispatchers.IO) {
        try {
            val json = fetchTable("map_countries")
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                MapCountry(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    bbox = obj.getString("bbox"),
                    graphUrl = obj.optString("graph_url").ifBlank { null },
                    geocodingUrl = obj.optString("geocoding_url").ifBlank { null },
                    graphSizeMb = obj.optDouble("graph_size_mb", 0.0),
                    geocodingSizeMb = obj.optDouble("geocoding_size_mb", 0.0)
                )
            }.also { DebugLog.log("catalog: fetched ${it.size} countries") }
        } catch (e: Exception) {
            DebugLog.log("catalog: fetch countries error: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchRegions(): List<MapRegionRemote> = withContext(Dispatchers.IO) {
        try {
            val json = fetchTable("map_regions")
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                MapRegionRemote(
                    id = obj.getString("id"),
                    countryId = obj.getString("country_id"),
                    name = obj.getString("name"),
                    mbtilesUrl = obj.getString("mbtiles_url"),
                    mbtilesSizeMb = obj.optDouble("mbtiles_size_mb", 0.0),
                    bbox = obj.getString("bbox")
                )
            }.also { DebugLog.log("catalog: fetched ${it.size} regions") }
        } catch (e: Exception) {
            DebugLog.log("catalog: fetch regions error: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun fetchTable(table: String): JSONArray {
        val url = "$supabaseUrl/rest/v1/$table?select=*"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Accept", "application/json")
            authRepository.accessToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
            conn.disconnect()
            throw Exception(error)
        }
        val result = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONArray(result)
    }
}
