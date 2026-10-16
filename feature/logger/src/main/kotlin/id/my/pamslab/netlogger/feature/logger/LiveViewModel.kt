package id.my.pamslab.netlogger.feature.logger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.data.LoggingSettings
import id.my.pamslab.netlogger.core.data.SpeedTestSettings
import id.my.pamslab.netlogger.core.location.LocationProvider
import id.my.pamslab.netlogger.core.device.NetworkRouteSource
import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.device.LinkCounters
import id.my.pamslab.netlogger.core.model.device.NetworkRoute
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
import kotlinx.coroutines.flow.scan
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
        /** The default network (Wi-Fi with SSID, cellular, …); null until known. */
        val route: NetworkRoute? = null,
        /** Byte counters read with this sample, and the recent readings the data rates are averaged over. */
        val counters: List<LinkCounters> = emptyList(),
    ) : LiveUiState {
        internal val rates: LinkRates? get() = counters.lastOrNull()?.let { linkRates(rateBase(counters), it, route?.transport) }
    }

    data class Failed(val message: String) : LiveUiState
}

@HiltViewModel
class LiveViewModel @Inject constructor(
    sampler: RadioSampler,
    location: LocationProvider,
    engine: LoggingEngine,
    clock: Clock,
    repository: SessionRepository,
    speedTestSettings: SpeedTestSettings,
    loggingSettings: LoggingSettings,
    private val livePrefs: LivePrefs,
    private val routeSource: NetworkRouteSource,
) : ViewModel() {

    /** The public secondary speed test target is offered (Settings). */
    val publicSpeedTest: StateFlow<Boolean> = speedTestSettings.publicTargetEnabled

    /** Tick interval of the next session (Settings). */
    val intervalMs: StateFlow<Long> = loggingSettings.intervalMs

    private val _scenario = MutableStateFlow(loggingSettings.defaultScenario.value ?: Scenario.DRIVE)

    /** Scenario for the next session: the default from Settings, else the last session's. */
    val scenario: StateFlow<Scenario> = _scenario.asStateFlow()

    init {
        // Follows the setting also when it is changed while Live is alive; a chip tapped on Live wins until then.
        viewModelScope.launch {
            loggingSettings.defaultScenario.collect { default ->
                (default ?: repository.latestScenario())?.let { _scenario.value = it }
            }
        }
    }

    /** Collapsible sections that are open (remembered across app starts). */
    val openSections: StateFlow<Set<LiveSection>> = livePrefs.open

    fun setOpen(section: LiveSection, open: Boolean) = livePrefs.setOpen(section, open)

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
        // One counter reading per radio sample; the rates are averaged over the recent readings (see rateBase).
        .scan<LiveUiState.Ready, LiveUiState.Ready?>(null) { prev, ready ->
            ready.copy(counters = keepRecent(prev?.counters.orEmpty() + routeSource.counters()))
        }
        .filterNotNull()
        .combine(routeSource.route) { ready, route -> ready.copy(route = route) }
        .map<LiveUiState.Ready, LiveUiState> { it }
        .catch { emit(LiveUiState.Failed(it.toString())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState.Waiting)

    private companion object {
        const val LIVE_INTERVAL_MS = 1_000L
    }
}
