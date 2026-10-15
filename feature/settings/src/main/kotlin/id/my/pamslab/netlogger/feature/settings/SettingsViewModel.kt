package id.my.pamslab.netlogger.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.SpeedTestSettings
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: SpeedTestSettings) : ViewModel() {
    val speedTest: StateFlow<SpeedTestConfig> = settings.config

    fun save(pingUrl: String, downloadUrl: String, uploadUrl: String) = settings.save(pingUrl, downloadUrl, uploadUrl)
}
