package id.my.pamslab.netlogger.feature.indoor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.FloorPlanRepository
import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.model.indoor.PlanGeometry
import id.my.pamslab.netlogger.feature.indoor.navigation.PlanScaleRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PlanScaleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FloorPlanRepository,
) : ViewModel() {
    private val planId = savedStateHandle.toRoute<PlanScaleRoute>().planId

    /** Null while loading, or for good when the plan was deleted meanwhile ([missing]). */
    private val _plan = MutableStateFlow<FloorPlanSummary?>(null)
    val plan: StateFlow<FloorPlanSummary?> = _plan.asStateFlow()

    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    init {
        viewModelScope.launch { repository.plan(planId)?.let { _plan.value = it } ?: run { _missing.value = true } }
    }

    fun save(metresPerPx: Double?, done: () -> Unit) = viewModelScope.launch {
        repository.setScale(planId, metresPerPx)
        done()
    }
}

/**
 * Scale calibration (decision 006, D4): tap two points on the plan whose real distance is known (a wall, a corridor,
 * a door width), enter the distance in metres, save. A third tap starts again.
 */
@Composable
internal fun PlanScaleScreen(onBack: () -> Unit, viewModel: PlanScaleViewModel = hiltViewModel()) {
    val summary by viewModel.plan.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.scale_back)) }
        val s = summary
        when {
            missing -> Text(stringResource(R.string.scale_missing))
            s == null -> CircularProgressIndicator()
            else -> ScaleContent(s, onSave = { viewModel.save(it, onBack) })
        }
    }
}

@Composable
private fun ColumnScope.ScaleContent(summary: FloorPlanSummary, onSave: (Double?) -> Unit) {
    val plan = summary.plan
    // Points in stored-image pixels; saveable as plain floats.
    var ax by rememberSaveable { mutableStateOf<Float?>(null) }
    var ay by rememberSaveable { mutableStateOf<Float?>(null) }
    var bx by rememberSaveable { mutableStateOf<Float?>(null) }
    var by by rememberSaveable { mutableStateOf<Float?>(null) }
    var distance by rememberSaveable { mutableStateOf("") }
    val metres = parseMetres(distance)
    val a = point(ax, ay)
    val b = point(bx, by)
    val result = if (a != null && b != null && metres != null) {
        PlanGeometry.metresPerPx(a.first.toDouble(), a.second.toDouble(), b.first.toDouble(), b.second.toDouble(), metres)
    } else {
        null
    }
    val pointColor = MaterialTheme.colorScheme.primary

    Text(stringResource(R.string.scale_title, plan.name), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.scale_info), style = MaterialTheme.typography.bodySmall)
    PlanView(
        summary.image, plan.widthPx, plan.heightPx,
        Modifier.fillMaxWidth().weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onTap = { x, y ->
            if (ax == null || bx != null) {
                ax = x; ay = y; bx = null; by = null
            } else {
                bx = x; by = y
            }
        },
    ) { v ->
        val sa = a?.let { v.toScreen(it.first, it.second) }
        val sb = b?.let { v.toScreen(it.first, it.second) }
        if (sa != null && sb != null) {
            drawLine(pointColor, Offset(sa.first, sa.second), Offset(sb.first, sb.second), strokeWidth = 4f)
        }
        listOfNotNull(sa, sb).forEach {
            drawCircle(Color.White, radius = 14f, center = Offset(it.first, it.second))
            drawCircle(pointColor, radius = 14f, center = Offset(it.first, it.second), style = Stroke(width = 5f))
        }
    }
    Text(
        when {
            a == null -> stringResource(R.string.scale_tap_a)
            b == null -> stringResource(R.string.scale_tap_b)
            else -> stringResource(R.string.scale_points_set)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            distance, { distance = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(8) },
            Modifier.weight(1f),
            label = { Text(stringResource(R.string.scale_distance)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Button(onClick = { onSave(result) }, enabled = result != null) { Text(stringResource(R.string.scale_save)) }
    }
    val tooClose = a != null && b != null && metres != null && result == null
    val saved = plan.metresPerPx
    Text(
        when {
            tooClose -> stringResource(R.string.scale_too_close)
            result != null -> stringResource(R.string.scale_result, formatPxPerMetre(result))
            saved != null -> stringResource(R.string.scale_current, formatPxPerMetre(saved))
            else -> stringResource(R.string.scale_none)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (tooClose) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (saved != null) {
        OutlinedButton(onClick = { onSave(null) }) { Text(stringResource(R.string.scale_remove)) }
    }
}

private fun point(x: Float?, y: Float?): Pair<Float, Float>? = if (x != null && y != null) x to y else null

/** "12.5" or "12,5" (comma-decimal locales) -> 12.5; null for anything else. */
internal fun parseMetres(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

private fun formatPxPerMetre(metresPerPx: Double) = String.format(Locale.ROOT, "%.1f", 1 / metresPerPx)
