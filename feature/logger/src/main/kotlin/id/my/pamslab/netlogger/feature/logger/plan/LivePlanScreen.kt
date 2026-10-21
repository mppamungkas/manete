package id.my.pamslab.netlogger.feature.logger.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.model.indoor.FloorPlan
import id.my.pamslab.netlogger.core.ui.plan.PlanView
import id.my.pamslab.netlogger.feature.logger.R
import id.my.pamslab.netlogger.feature.logger.RecordingRed
import id.my.pamslab.netlogger.feature.logger.service.IndoorState
import id.my.pamslab.netlogger.feature.logger.service.LoggingService
import id.my.pamslab.netlogger.feature.logger.service.LoggingState
import java.util.Locale

/**
 * Live › Plan (decision 006, step 3): the plan of the running session, full screen. Tapping the plan stores "I am here
 * now"; Still here repeats the last spot; Undo takes back the last tap; the plan menu changes the floor. Ticks between
 * taps are placed along the path and coloured by the chosen metric.
 */
@Composable
internal fun LivePlanScreen(onBack: () -> Unit, viewModel: LivePlanViewModel = hiltViewModel()) {
    val logging by viewModel.logging.collectAsStateWithLifecycle()
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val metric by viewModel.metric.collectAsStateWithLifecycle()
    val running = logging as? LoggingState.Running
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PlanStatus(running, onBack)
        val list = plans
        when {
            running == null -> Message(stringResource(R.string.plan_not_logging))
            list == null -> Unit
            list.isEmpty() -> Message(stringResource(R.string.plan_no_plans))
            else -> PlanContent(running, list, metric, viewModel)
        }
    }
}

@Composable
private fun PlanStatus(running: LoggingState.Running?, onBack: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.plan_back)) }
        if (running != null) {
            val nowNs = running.latest?.radio?.elapsedRealtimeNs ?: running.stats.startedElapsedNs
            Text("● ", color = RecordingRed, fontWeight = FontWeight.Bold)
            Text(
                pluralStringResource(
                    R.plurals.plan_rec, running.stats.recorded.toInt(),
                    formatElapsedShort(nowNs - running.stats.startedElapsedNs), running.stats.recorded,
                ),
                Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { LoggingService.addMarker(context) }) { Text(stringResource(R.string.live_marker)) }
        }
    }
}

@Composable
private fun ColumnScope.PlanContent(running: LoggingState.Running, plans: List<FloorPlanSummary>, metric: PlanMetric, viewModel: LivePlanViewModel) {
    val indoor = running.indoor
    val current = indoor.plan
    PlanPicker(plans, current, viewModel::choose)
    if (current == null) {
        Message(stringResource(R.string.plan_choose))
        return
    }
    val summary = plans.firstOrNull { it.plan.id == current.id }
    if (summary == null) {
        Message(stringResource(R.string.plan_deleted))
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlanMetric.entries.forEach { m -> FilterChip(m == metric, { viewModel.setMetric(m) }, { Text(m.label) }) }
    }
    val placed = remember(indoor, metric) { placedTicks(indoor, metric) }
    val path = remember(indoor) { indoor.active.filter { it.planId == current.id } }
    val last = indoor.lastActive?.waypoint?.takeIf { it.planId == current.id }
    val haptics = LocalHapticFeedback.current
    PlanView(
        summary.image, current.widthPx, current.heightPx,
        Modifier.fillMaxWidth().weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onTap = { x, y, _ ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.tap(x, y)
        },
    ) { v ->
        // The walked path: taps joined in time order on this plan (a white halo keeps it visible on dark plans).
        if (path.size > 1) {
            val line = Path().apply {
                path.forEachIndexed { i, w ->
                    val (sx, sy) = v.toScreen(w.x.toFloat(), w.y.toFloat())
                    if (i == 0) moveTo(sx, sy) else lineTo(sx, sy)
                }
            }
            drawPath(line, Color.White, style = Stroke(width = 7f))
            drawPath(line, PATH_COLOR, style = Stroke(width = 3f))
        }
        placed.forEach { t ->
            val (sx, sy) = v.toScreen(t.position.x.toFloat(), t.position.y.toFloat())
            drawCircle(Color.White, radius = TICK_RADIUS + 1.5f, center = Offset(sx, sy))
            drawCircle(Color(t.argb), radius = TICK_RADIUS, center = Offset(sx, sy))
        }
        path.forEach { w ->
            val (sx, sy) = v.toScreen(w.x.toFloat(), w.y.toFloat())
            drawCircle(PATH_COLOR, radius = 5f, center = Offset(sx, sy))
        }
        last?.let { w ->
            val (sx, sy) = v.toScreen(w.x.toFloat(), w.y.toFloat())
            val c = Offset(sx, sy)
            drawCircle(Color.White, radius = 22f, center = c, style = Stroke(width = 9f))
            drawCircle(HERE_COLOR, radius = 22f, center = c, style = Stroke(width = 5f))
        }
    }
    Legend(metric, placed)
    val nowNs = running.latest?.radio?.elapsedRealtimeNs
    Text(tapHint(indoor, current, nowNs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::stillHere, enabled = last != null) { Text(stringResource(R.string.plan_still_here)) }
        OutlinedButton(onClick = viewModel::undo, enabled = indoor.lastActive != null) { Text(stringResource(R.string.plan_undo)) }
    }
}

@Composable
private fun PlanPicker(plans: List<FloorPlanSummary>, current: FloorPlan?, onChoose: (FloorPlan) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current?.let { stringResource(R.string.plan_floor_button, IndoorState.label(it)) } ?: stringResource(R.string.plan_choose_button))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            plans.forEach { s ->
                DropdownMenuItem(text = { Text(IndoorState.label(s.plan)) }, onClick = { open = false; onChoose(s.plan) })
            }
        }
    }
}

@Composable
private fun Legend(metric: PlanMetric, placed: List<PlacedTick>) {
    val counts = remember(placed) { placed.groupingBy { it.argb }.eachCount() }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(metric.label, style = MaterialTheme.typography.labelMedium)
        counts.entries.sortedByDescending { it.value }.forEach { (argb, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).background(Color(argb), CircleShape))
                Text(n.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (placed.isEmpty()) Text(stringResource(R.string.plan_no_placed), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun tapHint(indoor: IndoorState, plan: FloorPlan, nowNs: Long?): String {
    val last = indoor.lastActive?.waypoint?.takeIf { it.planId == plan.id }
    val taps = indoor.active.count { it.planId == plan.id }
    if (last == null || nowNs == null) return stringResource(R.string.plan_hint_first)
    val ago = formatElapsedShort(nowNs - last.elapsedRealtimeNs)
    return pluralStringResource(R.plurals.plan_hint_taps, taps, taps, ago)
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
}

/** "4:07" or "1:02:07". */
internal fun formatElapsedShort(ns: Long): String {
    val s = (ns / 1_000_000_000).coerceAtLeast(0)
    return if (s >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}

private const val TICK_RADIUS = 7f

/** The walked path and taps: dark grey reads on white plans and next to the signal colours. */
private val PATH_COLOR = Color(0xFF37474F)

/** The last tap ("you are here"), the same magenta as the scale markers. */
private val HERE_COLOR = Color(0xFFD81B60)
