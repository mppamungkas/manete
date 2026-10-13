package id.my.pamslab.netlogger.feature.logger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.location.LocationProvider
import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.location.LocationState
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.telephony.RadioSampler
import id.my.pamslab.netlogger.feature.logger.service.LoggingEngine
import id.my.pamslab.netlogger.feature.logger.service.LoggingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LiveUiState {
    data object Waiting : LiveUiState

    data class Ready(
        val snapshot: RadioSnapshot,
        val location: LocationState?,
        /** Preview only: age of [location]'s fix at the radio snapshot time. */
        val locationAgeMs: Long?,
        /** Set while logging: the full tick (GPS match, device context). */
        val tick: Tick?,
        val logging: LoggingState,
    ) : LiveUiState

    data class Failed(val message: String) : LiveUiState
}

@HiltViewModel
class LiveViewModel @Inject constructor(
    sampler: RadioSampler,
    location: LocationProvider,
    engine: LoggingEngine,
    clock: Clock,
    repository: SessionRepository,
) : ViewModel() {

    private val _scenario = MutableStateFlow(Scenario.DRIVE)

    /** Scenario for the next session; defaults to the last session's. */
    val scenario: StateFlow<Scenario> = _scenario.asStateFlow()

    init {
        viewModelScope.launch { repository.latestScenario()?.let { _scenario.value = it } }
    }

    fun selectScenario(scenario: Scenario) {
        _scenario.value = scenario
    }

    /**
     * While logging, shows the service's ticks (one radio loop, not two). Otherwise samples a preview only while the
     * screen is visible. Location comes from the shared stream either way.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LiveUiState> = engine.state
        .map { it is LoggingState.Running }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (running) {
                engine.state.filterIsInstance<LoggingState.Running>()
                    .mapNotNull { s -> s.latest?.let { LiveUiState.Ready(it.radio, null, it.locationAgeMs, it, s) } }
            } else {
                combine(sampler.samples(LIVE_INTERVAL_MS), location.updates, engine.state) { radio, loc, logging ->
                    val age = loc.fix?.let { (clock.elapsedRealtimeNs() - it.elapsedRealtimeNs) / 1_000_000 }
                    LiveUiState.Ready(radio, loc, age, null, logging)
                }
            }
        }
        .filterNotNull()
        .map<LiveUiState.Ready, LiveUiState> { it }
        .catch { emit(LiveUiState.Failed(it.toString())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState.Waiting)

    private companion object {
        const val LIVE_INTERVAL_MS = 1_000L
    }
}
