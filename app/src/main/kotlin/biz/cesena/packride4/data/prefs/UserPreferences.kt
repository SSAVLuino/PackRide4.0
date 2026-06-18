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

    companion object {
        private const val KEY_USE_OFFLINE_MAP = "use_offline_map"
    }
}
