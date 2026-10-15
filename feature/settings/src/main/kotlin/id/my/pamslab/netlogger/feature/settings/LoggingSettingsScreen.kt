package id.my.pamslab.netlogger.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.LoggingSettings
import id.my.pamslab.netlogger.core.model.raw.RawSubscription
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.telephony.TelephonySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoggingSettingsViewModel @Inject constructor(
    private val settings: LoggingSettings,
    private val telephony: TelephonySource,
) : ViewModel() {
    val intervalMs: StateFlow<Long> = settings.intervalMs
    val defaultScenario: StateFlow<Scenario?> = settings.defaultScenario

    private val _dataSim = MutableStateFlow<RawSubscription?>(null)
    val dataSim: StateFlow<RawSubscription?> = _dataSim.asStateFlow()

    fun setInterval(intervalMs: Long) = settings.setIntervalMs(intervalMs)

    fun setDefaultScenario(scenario: Scenario?) = settings.setDefaultScenario(scenario)

    /** Reads the current data SIM (null without one or without READ_PHONE_STATE). */
    fun refreshDataSim() {
        viewModelScope.launch(Dispatchers.Default) { _dataSim.value = telephony.activeDataSubscription() }
    }
}

@Composable
internal fun LoggingSettingsScreen(onBack: () -> Unit, viewModel: LoggingSettingsViewModel = hiltViewModel()) {
    val interval by viewModel.intervalMs.collectAsStateWithLifecycle()
    val scenario by viewModel.defaultScenario.collectAsStateWithLifecycle()
    val sim by viewModel.dataSim.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshDataSim() }

    SettingsPage(stringResource(R.string.settings_logging), onBack) {
        SettingCard(stringResource(R.string.logging_interval_title), stringResource(R.string.logging_interval_info)) {
            Choices(
                options = LoggingSettings.INTERVALS_MS,
                selected = interval,
                label = {
                    val text = stringResource(R.string.logging_interval_option, it.toInt())
                    if (it == LoggingSettings.DEFAULT_INTERVAL_MS) stringResource(R.string.option_recommended, text) else text
                },
                description = { stringResource(intervalInfoRes(it)) },
                onSelect = viewModel::setInterval,
            )
        }
        SettingCard(stringResource(R.string.logging_scenario_title), stringResource(R.string.logging_scenario_info)) {
            Choices(
                options = listOf<Scenario?>(null) + Scenario.entries,
                selected = scenario,
                label = { stringResource(it?.labelRes() ?: R.string.logging_scenario_last) },
                description = { stringResource(it?.infoRes() ?: R.string.logging_scenario_last_info) },
                onSelect = viewModel::setDefaultScenario,
            )
        }
        SettingCard(stringResource(R.string.logging_sim_title), stringResource(R.string.logging_sim_info)) {
            val unknown = stringResource(R.string.unknown)
            Text(
                sim?.let {
                    stringResource(
                        R.string.logging_sim_current,
                        it.carrierName ?: unknown,
                        if (it.mcc != null && it.mnc != null) "${it.mcc}-${it.mnc}" else unknown,
                        it.simSlot?.let { slot -> (slot + 1).toString() } ?: unknown,
                    )
                } ?: stringResource(R.string.logging_sim_none),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun intervalInfoRes(intervalMs: Long): Int = when (intervalMs) {
    500L -> R.string.logging_interval_500
    1_000L -> R.string.logging_interval_1000
    2_000L -> R.string.logging_interval_2000
    else -> R.string.logging_interval_5000
}
