package id.my.pamslab.netlogger.feature.indoor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.FloorPlanRepository
import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.location.LocationProvider
import id.my.pamslab.netlogger.core.model.indoor.GeoPoint
import id.my.pamslab.netlogger.core.model.indoor.Georeference
import id.my.pamslab.netlogger.feature.indoor.navigation.PlanGeorefRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** One point being placed: on the plan, on the map, or both. */
data class PointPair(val x: Double? = null, val y: Double? = null, val lat: Double? = null, val lon: Double? = null) {
    val onPlan: Boolean get() = x != null && y != null
    val onMap: Boolean get() = lat != null && lon != null
    val complete: Boolean get() = onPlan && onMap

    fun geoPoint(): GeoPoint? = if (x != null && y != null && lat != null && lon != null) GeoPoint(x, y, lat, lon) else null
}

/**
 * Placing a plan on the map (decision 006 step 7): up to [MAX_POINTS] points, each tapped on the plan and on the map,
 * in either order. While the last point is incomplete, a tap sets (or corrects) its side; otherwise it starts the next
 * point.
 */
fun withPlanTap(points: List<PointPair>, x: Double, y: Double): List<PointPair> {
    val last = points.lastOrNull()
    return when {
        last != null && !last.complete -> points.dropLast(1) + last.copy(x = x, y = y)
        points.size < MAX_POINTS -> points + PointPair(x = x, y = y)
        else -> points
    }
}

fun withMapTap(points: List<PointPair>, lat: Double, lon: Double): List<PointPair> {
    val last = points.lastOrNull()
    return when {
        last != null && !last.complete -> points.dropLast(1) + last.copy(lat = lat, lon = lon)
        points.size < MAX_POINTS -> points + PointPair(lat = lat, lon = lon)
        else -> points
    }
}

/** Two points place the plan; a third checks the placement (its error is shown). */
const val MAX_POINTS = 3

@HiltViewModel
class PlanGeorefViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FloorPlanRepository,
    private val location: LocationProvider,
) : ViewModel() {
    private val planId = savedStateHandle.toRoute<PlanGeorefRoute>().planId

    private val _plan = MutableStateFlow<FloorPlanSummary?>(null)
    val plan: StateFlow<FloorPlanSummary?> = _plan.asStateFlow()

    private val _points = MutableStateFlow<List<PointPair>>(emptyList())
    val points: StateFlow<List<PointPair>> = _points.asStateFlow()

    /** Where the map opens: the saved placement, else the phone's position (lat, lon), else null (whole world). */
    private val _start = MutableStateFlow<Pair<Double, Double>?>(null)
    val start: StateFlow<Pair<Double, Double>?> = _start.asStateFlow()

    init {
        viewModelScope.launch {
            val summary = repository.plan(planId) ?: return@launch
            _plan.value = summary
            val saved = summary.plan.georef?.points.orEmpty()
            _points.value = saved.map { PointPair(it.x, it.y, it.lat, it.lon) }
            _start.value = saved.firstOrNull()?.let { it.lat to it.lon }
                ?: withTimeoutOrNull(START_FIX_TIMEOUT_MS) {
                    location.updates.mapNotNull { it.fix }.first().let { it.latitude to it.longitude }
                }
        }
    }

    /** The placement the current points give; null with fewer than two complete points. */
    fun georef(points: List<PointPair>): Georeference? =
        points.mapNotNull { it.geoPoint() }.takeIf { it.size >= 2 }?.let(::Georeference)

    fun tapPlan(x: Float, y: Float) {
        _points.value = withPlanTap(_points.value, x.toDouble(), y.toDouble())
    }

    fun tapMap(lat: Double, lon: Double) {
        _points.value = withMapTap(_points.value, lat, lon)
    }

    fun undo() {
        _points.value = _points.value.dropLast(1)
    }

    fun save(done: () -> Unit) = viewModelScope.launch {
        georef(_points.value)?.takeIf { it.transform != null }?.let { repository.setGeoreference(planId, it) }
        done()
    }

    fun remove(done: () -> Unit) = viewModelScope.launch {
        repository.setGeoreference(planId, null)
        done()
    }

    private companion object {
        /** Indoors the first fix may never come: the map then opens on the whole world. */
        const val START_FIX_TIMEOUT_MS = 4_000L
    }
}
