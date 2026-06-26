package biz.cesena.packride4.ui.settings

import androidx.lifecycle.ViewModel
import biz.cesena.packride4.data.prefs.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences
) : ViewModel() {

    val useOfflineMap: StateFlow<Boolean> = prefs.useOfflineMap
    val voiceMode: StateFlow<String> = prefs.voiceMode
    val showProgressBar: StateFlow<Boolean> = prefs.showProgressBar

    fun setUseOfflineMap(value: Boolean) = prefs.setUseOfflineMap(value)
    fun setVoiceMode(value: String) = prefs.setVoiceMode(value)
    fun setShowProgressBar(value: Boolean) = prefs.setShowProgressBar(value)
}
