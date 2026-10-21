package id.my.pamslab.netlogger.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MapScreen(modifier: Modifier = Modifier, viewModel: MapViewModel = hiltViewModel()) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val metric by viewModel.metric.collectAsStateWithLifecycle()
    val selectedSeq by viewModel.selectedSeq.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val planTrack by viewModel.planTrack.collectAsStateWithLifecycle()
    var follow by rememberSaveable { mutableStateOf(true) }

    val live = mode == MapMode.LIVE
    val wantedId = when {
        live && recording != null -> recording?.id
        live -> LiveTrail.LIVE_ID
        else -> selectedId
    }
    // Only draw a track that belongs to what is selected (avoids a frame of the previous one).
    val shown = content.track?.takeIf { it.sessionId == wantedId }
    // The floor plan view, when the shown session has taps on a floor plan (decision 006).
    val onPlan = planTrack?.takeIf { it.sessionId == wantedId }
    val plan = onPlan?.takeIf { view == MapView.PLAN }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dropdown(
                text = if (live) "Live session" else "Recorded session",
                options = listOf("Live session" to MapMode.LIVE, "Recorded session" to MapMode.RECORDED),
                onPick = viewModel::setMode,
            )
            if (!live) {
                val session = sessions?.firstOrNull { it.id == selectedId }
                Dropdown(
                    text = session?.let(::sessionTitle) ?: "No session",
                    options = sessions.orEmpty().map { s ->
                        sessionTitle(s) + (s.scenario?.let { " · ${it.name.lowercase()}" } ?: "") + " · ${s.measurements} ticks" to s.id
                    },
                    onPick = viewModel::pickSession,
                )
            }
            if (onPlan != null) {
                Dropdown(
                    text = if (plan != null) "Floor plan" else "Map",
                    options = listOf("Map" to MapView.MAP, "Floor plan" to MapView.PLAN),
                    onPick = viewModel::setView,
                )
            }
            if (plan != null && plan.plans.size > 1) {
                Dropdown(text = planLabel(plan.plan), options = plan.plans.map { planLabel(it) to it.plan.id }, onPick = viewModel::pickPlan)
            }
            Dropdown(text = metric.label, options = Metric.entries.map { it.label to it }, onPick = viewModel::setMetric)
            if (live && plan == null) FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackMap(
                track = shown,
                metric = metric,
                selectedSeq = selectedSeq,
                fitKey = wantedId,
                follow = live && follow,
                onPointClick = viewModel::selectPoint,
                modifier = Modifier.fillMaxSize(),
            )
            hint(live, recording != null, content, shown, sessions)?.takeIf { plan == null }?.let { Hint(it) }
            // Drawn over the map, which stays composed underneath: tearing down a MapView crashes MapLibre (2026-10-16).
            plan?.let { FloorPlanMap(it, metric, selectedSeq, viewModel::selectPoint, Modifier.fillMaxSize()) }
            Column(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                Legend(metric, plan?.points?.map { it.values } ?: shown?.points.orEmpty().map { it.values })
                if (plan == null) {
                    Text(
                        "© OpenStreetMap contributors",
                        Modifier.padding(top = 4.dp).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                    )
                }
            }
            plan?.let { p ->
                Text(
                    "${p.points.size} / ${p.ticks} ticks on this floor · ${p.path.size} taps" + if (live) " · recording" else "",
                    Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                )
            }
            shown?.takeIf { plan == null }?.let { t ->
                Text(
                    "${t.points.size} / ${t.ticks} ticks with GPS · ${t.events.size} events" + when {
                        content.preview -> " · live preview, not recorded"
                        live -> " · recording"
                        else -> ""
                    },
                    Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                )
            }
        }
        if (plan != null) {
            plan.points.firstOrNull { it.values.seq == selectedSeq }?.let { p ->
                PointCard(p.values, shown?.points?.firstOrNull { it.seq == selectedSeq }, planLine(plan, p)) { viewModel.selectPoint(null) }
            }
        } else {
            shown?.points?.firstOrNull { it.seq == selectedSeq }?.let { PointCard(it.values, it, null) { viewModel.selectPoint(null) } }
        }
    }
}

/** Why nothing (or little) is drawn; null when the track speaks for itself. */
private fun hint(live: Boolean, recording: Boolean, content: MapContent, shown: Track?, sessions: List<SessionSummary>?): String? = when {
    !live && sessions?.isEmpty() == true -> "No sessions yet. Start logging on the Live screen."
    live && !recording && content.locationStatus == LocationStatus.NO_PERMISSION -> "Live preview needs the location permission."
    live && !recording && content.locationStatus == LocationStatus.DISABLED -> "Live preview: location is switched off."
    shown == null -> null
    shown.points.isNotEmpty() -> null
    content.preview -> "Live preview: waiting for a GPS fix (not recorded)."
    shown.ticks == 0L -> "No ticks yet."
    else -> "None of the ${shown.ticks} ticks has a GPS fix."
}

/** A button showing [text] that opens a menu of [options] (label, value). */
@Composable
private fun <T> Dropdown(text: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty()) { Text("$text ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}

private fun sessionTitle(s: SessionSummary): String =
    (s.label?.takeIf { it.isNotBlank() } ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(s.startedAtMs))) +
        if (s.endedAtMs == null) " (recording)" else ""

private fun planLabel(s: FloorPlanSummary) = listOfNotNull(s.plan.name, s.plan.floor).joinToString(" · ")

/** "Floor: C · 2 · x 1019, y 996 px (20.4, 19.9 m) · 3.2 s from a tap". */
private fun planLine(track: PlanTrack, p: PlanPoint): String {
    val pos = p.position
    val metres = pos.metres(track.plan.plan)?.let { (x, y) -> " (%.1f, %.1f m)".format(Locale.ROOT, x, y) } ?: ""
    return "${planLabel(track.plan)} · x %.0f, y %.0f px".format(Locale.ROOT, pos.x, pos.y) + metres +
        " · %.1f s from a tap".format(Locale.ROOT, pos.waypointGapS)
}

/** Colour, range and how many shown ticks fall in it. */
@Composable
private fun Legend(metric: Metric, points: List<TickValues>) {
    Column(Modifier.background(Color.White.copy(alpha = 0.85f)).padding(4.dp)) {
        metric.legendCounts(points).forEach { (argb, text, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(argb), CircleShape))
                Text(" $text", style = MaterialTheme.typography.labelSmall, color = Color.Black)
                Text(
                    "  $count",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** The values of tick [p]; [gps] its fix if it has one; [plan] its floor plan position (plan view). */
@Composable
private fun PointCard(p: TickValues, gps: TrackPoint?, plan: String?, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tick ${p.seq} · " + SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(p.wallTimeMs)) + " · ${p.networkType}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onClose) { Text("Close") }
            }
            Value("LTE", "PCI ${p.ltePci ?: "–"} · EARFCN ${p.lteEarfcn ?: "–"} · B${p.lteBand ?: "–"}" + age(p.lteAgeMs))
            Value("LTE RSRP / SINR", "${dbm(p.lteRsrp, p.lteRsrpSource)} / ${dbm(p.lteSinr, p.lteSinrSource)}")
            Value("NR", "PCI ${p.nrPci ?: "–"} · ARFCN ${p.nrArfcn ?: "–"} · n${p.nrBand ?: "–"}" + (p.nrServingSource?.let { " · $it" } ?: "") + age(p.nrAgeMs))
            Value("NR RSRP / SINR", "${dbm(p.nrRsrp, p.nrRsrpSource)} / ${dbm(p.nrSinr, p.nrSinrSource)}")
            plan?.let { Value("Floor plan", it) }
            Value(
                "GPS",
                gps?.let { g ->
                    "%.6f, %.6f · ±%s m".format(Locale.ROOT, g.lat, g.lon, g.gpsAccuracyM?.let { "%.0f".format(Locale.ROOT, it) } ?: "–") + age(g.locationAgeMs)
                } ?: "no fix",
            )
        }
    }
}

private fun dbm(v: Int?, source: String?) = v?.let { "$it" + if (source == "SIGNAL_STRENGTH") " (SS)" else "" } ?: "–"

private fun age(ms: Long?) = ms?.let { " · age %.1f s".format(Locale.ROOT, it / 1000.0) } ?: ""

@Composable
private fun Value(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.32f), style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(0.68f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, Modifier.background(Color.White.copy(alpha = 0.85f)).padding(8.dp), color = Color.Black)
    }
}
