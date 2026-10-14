package id.my.pamslab.netlogger.feature.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.SessionRepository
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
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: SessionRepository,
    loader: TrackLoader,
) : ViewModel() {

    /** Newest first; null until the first database read. */
    val sessions: StateFlow<List<SessionSummary>?> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // null = follow the newest session (a recording session is always the newest).
    private val picked = MutableStateFlow(savedStateHandle.toRoute<MapRoute>().sessionId)

    val selectedId: StateFlow<Long?> = combine(picked, sessions) { id, list -> id ?: list?.firstOrNull()?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val track: StateFlow<Track?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else loader.track(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _metric = MutableStateFlow(Metric.LTE_RSRP)
    val metric: StateFlow<Metric> = _metric.asStateFlow()

    private val _selectedSeq = MutableStateFlow<Long?>(null)
    val selectedSeq: StateFlow<Long?> = _selectedSeq.asStateFlow()

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
