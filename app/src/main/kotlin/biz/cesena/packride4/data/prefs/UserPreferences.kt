package biz.cesena.packride4.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RecentDestination(val name: String, val lat: Double, val lon: Double)

@Serializable
data class FavoritePlace(val id: String, val name: String, val icon: String, val lat: Double, val lon: Double)

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("packride_prefs", Context.MODE_PRIVATE)

    private val _useOfflineMap = MutableStateFlow(prefs.getBoolean(KEY_USE_OFFLINE_MAP, true))
    val useOfflineMap: StateFlow<Boolean> = _useOfflineMap.asStateFlow()

    fun setUseOfflineMap(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_OFFLINE_MAP, value).apply()
        _useOfflineMap.value = value
    }

    fun getLastPosition(): Pair<Double, Double>? {
        val lat = prefs.getFloat(KEY_LAST_LAT, Float.MIN_VALUE)
        val lon = prefs.getFloat(KEY_LAST_LON, Float.MIN_VALUE)
        if (lat == Float.MIN_VALUE || lon == Float.MIN_VALUE) return null
        return lat.toDouble() to lon.toDouble()
    }

    fun saveLastPosition(lat: Double, lon: Double) {
        prefs.edit()
            .putFloat(KEY_LAST_LAT, lat.toFloat())
            .putFloat(KEY_LAST_LON, lon.toFloat())
            .apply()
    }

    // Voice announcement mode: "both", "first_only", "second_only", "none"
    private val _voiceMode = MutableStateFlow(prefs.getString(KEY_VOICE_MODE, "both") ?: "both")
    val voiceMode: StateFlow<String> = _voiceMode.asStateFlow()

    fun setVoiceMode(value: String) {
        prefs.edit().putString(KEY_VOICE_MODE, value).apply()
        _voiceMode.value = value
    }

    private val _showProgressBar = MutableStateFlow(prefs.getBoolean(KEY_SHOW_PROGRESS_BAR, true))
    val showProgressBar: StateFlow<Boolean> = _showProgressBar.asStateFlow()

    fun setShowProgressBar(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_PROGRESS_BAR, value).apply()
        _showProgressBar.value = value
    }

    fun getWidgetSelection(side: String, navigating: Boolean): String {
        val key = "widget_${side}_${if (navigating) "nav" else "idle"}"
        val default = when {
            side == "left" && !navigating -> "altitude"
            side == "right" && !navigating -> "time"
            side == "left" && navigating -> "km_remaining"
            else -> "altitude"
        }
        return prefs.getString(key, default) ?: default
    }

    fun setWidgetSelection(side: String, navigating: Boolean, value: String) {
        val key = "widget_${side}_${if (navigating) "nav" else "idle"}"
        prefs.edit().putString(key, value).apply()
    }

    fun getRecentDestinations(): List<RecentDestination> {
        val json = prefs.getString(KEY_RECENT_DESTINATIONS, null) ?: return emptyList()
        return try { Json.decodeFromString(json) } catch (_: Exception) { emptyList() }
    }

    fun saveRecentDestination(name: String, lat: Double, lon: Double) {
        val current = getRecentDestinations().toMutableList()
        current.removeAll { it.lat == lat && it.lon == lon }
        current.add(0, RecentDestination(name, lat, lon))
        prefs.edit().putString(KEY_RECENT_DESTINATIONS, Json.encodeToString(current.take(10))).apply()
    }

    fun getFavorites(): List<FavoritePlace> {
        val json = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try { Json.decodeFromString(json) } catch (_: Exception) { emptyList() }
    }

    fun saveFavorite(fav: FavoritePlace) {
        val current = getFavorites().toMutableList()
        current.removeAll { it.id == fav.id }
        current.add(0, fav)
        prefs.edit().putString(KEY_FAVORITES, Json.encodeToString(current)).apply()
    }

    fun deleteFavorite(id: String) {
        val current = getFavorites().toMutableList()
        current.removeAll { it.id == id }
        prefs.edit().putString(KEY_FAVORITES, Json.encodeToString(current)).apply()
    }

    companion object {
        private const val KEY_USE_OFFLINE_MAP = "use_offline_map"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        private const val KEY_VOICE_MODE = "voice_announcement_mode"
        private const val KEY_SHOW_PROGRESS_BAR = "show_progress_bar"
        private const val KEY_RECENT_DESTINATIONS = "recent_destinations"
        private const val KEY_FAVORITES = "favorites"
    }
}
