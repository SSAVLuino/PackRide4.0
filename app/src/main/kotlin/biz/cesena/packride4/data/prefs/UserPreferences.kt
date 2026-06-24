package biz.cesena.packride4.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

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

    companion object {
        private const val KEY_USE_OFFLINE_MAP = "use_offline_map"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        private const val KEY_VOICE_MODE = "voice_announcement_mode"
    }
}
