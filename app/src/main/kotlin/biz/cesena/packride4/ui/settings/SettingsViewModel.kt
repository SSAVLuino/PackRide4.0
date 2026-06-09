package biz.cesena.packride4.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.local.appDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MapSourceMode { ONLINE, OFFLINE, AUTO }

data class AppPrefs(
    val voiceGuidanceEnabled: Boolean = true,
    val maxSpeedKph: Int = 110,
    val gpsIntervalSeconds: Int = 2,
    val autoExportGpx: Boolean = false,
    val offlineMinutes: Int = 15,
    val pollIntervalSeconds: Int = 15,
    val mapSourceMode: MapSourceMode = MapSourceMode.AUTO
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private object Keys {
        val VOICE_GUIDANCE = booleanPreferencesKey("voice_guidance")
        val MAX_SPEED = intPreferencesKey("max_speed_kph")
        val GPS_INTERVAL = intPreferencesKey("gps_interval_seconds")
        val AUTO_EXPORT_GPX = booleanPreferencesKey("auto_export_gpx")
        val OFFLINE_MINUTES = intPreferencesKey("offline_minutes")
        val POLL_INTERVAL = intPreferencesKey("poll_interval_seconds")
        val MAP_SOURCE = stringPreferencesKey("map_source_mode")
    }

    val prefs: StateFlow<AppPrefs> = context.appDataStore.data
        .map { p ->
            AppPrefs(
                voiceGuidanceEnabled = p[Keys.VOICE_GUIDANCE] ?: true,
                maxSpeedKph = p[Keys.MAX_SPEED] ?: 110,
                gpsIntervalSeconds = p[Keys.GPS_INTERVAL] ?: 2,
                autoExportGpx = p[Keys.AUTO_EXPORT_GPX] ?: false,
                offlineMinutes = p[Keys.OFFLINE_MINUTES] ?: 15,
                pollIntervalSeconds = p[Keys.POLL_INTERVAL] ?: 15,
                mapSourceMode = runCatching {
                    MapSourceMode.valueOf(p[Keys.MAP_SOURCE] ?: "AUTO")
                }.getOrDefault(MapSourceMode.AUTO)
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())

    fun setVoiceGuidance(v: Boolean) = set { it[Keys.VOICE_GUIDANCE] = v }
    fun setMaxSpeed(v: Int) = set { it[Keys.MAX_SPEED] = v }
    fun setGpsInterval(v: Int) = set { it[Keys.GPS_INTERVAL] = v }
    fun setAutoExportGpx(v: Boolean) = set { it[Keys.AUTO_EXPORT_GPX] = v }
    fun setOfflineMinutes(v: Int) = set { it[Keys.OFFLINE_MINUTES] = v }
    fun setPollInterval(v: Int) = set { it[Keys.POLL_INTERVAL] = v }
    fun setMapSourceMode(v: MapSourceMode) = set { it[Keys.MAP_SOURCE] = v.name }

    private fun set(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch { context.appDataStore.edit { block(it) } }
    }
}
