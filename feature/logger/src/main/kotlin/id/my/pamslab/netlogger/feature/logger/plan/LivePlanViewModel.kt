package id.my.pamslab.netlogger.feature.logger.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.FloorPlanRepository
import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.model.indoor.FloorPlan
import id.my.pamslab.netlogger.core.model.indoor.PlanInterpolator
import id.my.pamslab.netlogger.core.model.indoor.PlanPosition
import id.my.pamslab.netlogger.core.ui.signal.NetworkColors
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel
import id.my.pamslab.netlogger.feature.logger.service.IndoorState
import id.my.pamslab.netlogger.feature.logger.service.LoggingEngine
import id.my.pamslab.netlogger.feature.logger.service.LoggingState
import id.my.pamslab.netlogger.feature.logger.service.PlanTick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What colours the ticks on the Live plan view: the Map's metrics (CLAUDE.md colour rules). */
enum class PlanMetric(val label: String) {
    LTE_RSRP("LTE RSRP"),
    NR_RSRP("NR RSRP"),
    LTE_SINR("LTE SINR"),
    NR_SINR("NR SINR"),
    NETWORK("Network"),
    ;

    fun argb(t: PlanTick): Long = when (this) {
        LTE_RSRP -> SignalLevel.rsrp(t.lteRsrp).argb
        NR_RSRP -> SignalLevel.rsrp(t.nrRsrp).argb
        LTE_SINR -> SignalLevel.sinr(t.lteSinr).argb
        NR_SINR -> SignalLevel.sinr(t.nrSinr).argb
        NETWORK -> NetworkColors.ARGB[t.networkType] ?: SignalLevel.NONE.argb
    }
}

/** A tick placed on the current plan, with its colour. */
data class PlacedTick(val position: PlanPosition, val argb: Long)

/**
 * The ticks of [indoor] that fall on its current plan (decision 006: between two taps on that plan, by time), coloured
 * by [metric]. Pure, so the Live view and its test agree.
 */
fun placedTicks(indoor: IndoorState, metric: PlanMetric): List<PlacedTick> {
    val plan = indoor.plan ?: return emptyList()
    val positions = PlanInterpolator.positions(indoor.ticks.map { it.elapsedRealtimeNs }, indoor.active)
    return indoor.ticks.zip(positions).mapNotNull { (tick, p) ->
        p?.takeIf { it.planId == plan.id }?.let { PlacedTick(it, metric.argb(tick)) }
    }
}

@HiltViewModel
class LivePlanViewModel @Inject constructor(
    private val engine: LoggingEngine,
    floorPlans: FloorPlanRepository,
) : ViewModel() {

    val logging: StateFlow<LoggingState> = engine.state

    /** Null until loaded. */
    val plans: StateFlow<List<FloorPlanSummary>?> = floorPlans.plans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _metric = MutableStateFlow(PlanMetric.LTE_RSRP)
    val metric: StateFlow<PlanMetric> = _metric.asStateFlow()

    fun setMetric(metric: PlanMetric) {
        _metric.value = metric
    }

    fun choose(plan: FloorPlan) = viewModelScope.launch { engine.setPlan(plan) }

    fun tap(x: Float, y: Float) = viewModelScope.launch { engine.addWaypoint(x.toDouble(), y.toDouble()) }

    fun stillHere() = viewModelScope.launch { engine.stillHere() }

    fun undo() = viewModelScope.launch { engine.undoWaypoint() }
}
