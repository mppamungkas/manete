package id.my.pamslab.netlogger.feature.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.feature.map.navigation.MapRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the Map shows: the live session (the recording one, or a preview while not logging) or a recorded one. */
enum class MapMode { LIVE, RECORDED }

/** The shown track and where it comes from. [locationStatus] only for the live preview. */
data class MapContent(val track: Track?, val preview: Boolean, val locationStatus: LocationStatus? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: SessionRepository,
    loader: TrackLoader,
    liveTrail: LiveTrail,
) : ViewModel() {

    private val routeSession = savedStateHandle.toRoute<MapRoute>().sessionId

    /** Newest first; null until the first database read. */
    val sessions: StateFlow<List<SessionSummary>?> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The session recording now, if any. */
    val recording: StateFlow<SessionSummary?> = sessions
        .map { list -> list?.firstOrNull { it.endedAtMs == null } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // "Show on map" from a session opens that recorded session; the Map tab opens live.
    private val _mode = MutableStateFlow(if (routeSession != null) MapMode.RECORDED else MapMode.LIVE)
    val mode: StateFlow<MapMode> = _mode.asStateFlow()

    // null = the newest session.
    private val picked = MutableStateFlow(routeSession)

    /** The recorded session picked in "Recorded session". */
    val selectedId: StateFlow<Long?> = combine(picked, sessions) { id, list -> id ?: list?.firstOrNull()?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Live: the recording session's track, or, while not logging, the in-memory preview (radio and location are read
     * only while this is collected, i.e. while the Map is visible). Recorded: the picked session.
     */
    val content: StateFlow<MapContent> = combine(_mode, recording.map { it?.id }.distinctUntilChanged(), selectedId) { m, rec, sel -> Triple(m, rec, sel) }
        .distinctUntilChanged()
        .flatMapLatest { (m, rec, sel) ->
            when {
                m == MapMode.LIVE && rec != null -> loader.track(rec).map { MapContent(it, preview = false) }
                m == MapMode.LIVE -> liveTrail.track().map { MapContent(it.track, preview = true, locationStatus = it.locationStatus) }
                sel != null -> loader.track(sel).map { MapContent(it, preview = false) }
                else -> flowOf(MapContent(null, preview = false))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapContent(null, preview = false))

    private val _metric = MutableStateFlow(Metric.LTE_RSRP)
    val metric: StateFlow<Metric> = _metric.asStateFlow()

    private val _selectedSeq = MutableStateFlow<Long?>(null)
    val selectedSeq: StateFlow<Long?> = _selectedSeq.asStateFlow()

    fun setMode(mode: MapMode) {
        _mode.value = mode
        _selectedSeq.value = null
    }

    fun pickSession(id: Long) {
        picked.value = id
        _selectedSeq.value = null
    }

    fun setMetric(metric: Metric) {
        _metric.value = metric
    }

    fun selectPoint(seq: Long?) {
        _selectedSeq.value = seq
    }
}
