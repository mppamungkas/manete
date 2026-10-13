package id.ac.pnp.netlogger.feature.logger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.ac.pnp.netlogger.core.model.radio.RadioSnapshot
import id.ac.pnp.netlogger.core.telephony.RadioSampler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface LiveUiState {
    data object Waiting : LiveUiState
    data class Ready(val snapshot: RadioSnapshot) : LiveUiState
    data class Failed(val message: String) : LiveUiState
}

@HiltViewModel
class LiveViewModel @Inject constructor(sampler: RadioSampler) : ViewModel() {

    /** Samples only while the Live screen is visible; the logging service runs its own loop (milestone 3). */
    val state: StateFlow<LiveUiState> = sampler.samples(LIVE_INTERVAL_MS)
        .map<RadioSnapshot, LiveUiState> { LiveUiState.Ready(it) }
        .catch { emit(LiveUiState.Failed(it.toString())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState.Waiting)

    private companion object {
        const val LIVE_INTERVAL_MS = 1_000L
    }
}
