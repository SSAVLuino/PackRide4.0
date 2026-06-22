package biz.cesena.packride4.data.download

import biz.cesena.packride4.debug.DebugLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class MapCountry(
    val id: String,
    val name: String,
    val bbox: String,
    @SerialName("graph_url") val graphUrl: String? = null,
    @SerialName("geocoding_url") val geocodingUrl: String? = null,
    @SerialName("graph_size_mb") val graphSizeMb: Double = 0.0,
    @SerialName("geocoding_size_mb") val geocodingSizeMb: Double = 0.0
)

@Serializable
data class MapRegionRemote(
    val id: String,
    @SerialName("country_id") val countryId: String,
    val name: String,
    @SerialName("mbtiles_url") val mbtilesUrl: String,
    @SerialName("mbtiles_size_mb") val mbtilesSizeMb: Double = 0.0,
    val bbox: String
)

@Singleton
class MapCatalogRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    suspend fun fetchCountries(): List<MapCountry> {
        return try {
            supabase.postgrest.from("map_countries").select().decodeList<MapCountry>().also {
                DebugLog.log("catalog: fetched ${it.size} countries")
            }
        } catch (e: Exception) {
            DebugLog.log("catalog: fetch countries error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchRegions(): List<MapRegionRemote> {
        return try {
            supabase.postgrest.from("map_regions").select().decodeList<MapRegionRemote>().also {
                DebugLog.log("catalog: fetched ${it.size} regions")
            }
        } catch (e: Exception) {
            DebugLog.log("catalog: fetch regions error: ${e.message}")
            emptyList()
        }
    }
}
