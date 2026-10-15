package id.my.pamslab.netlogger.feature.sessions.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.pamslab.netlogger.core.ui.signal.NetworkColors
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LTE_COLOR = NetworkColors.color("LTE")
private val NR_COLOR = NetworkColors.color("NR_NSA")
private val TEST_COLOR = Color(0xFF00897B)
private val SCREEN_OFF_COLOR = Color(0xFF424242)
private const val LABEL_WIDTH_DP = 40
private const val BAND_ALPHA = 0.14f

/** Background bands of a chart, the map colors: (from, to, level). */
private val RSRP_BANDS = listOf(
    Triple(-80.0, Double.MAX_VALUE, SignalLevel.GOOD), Triple(-95.0, -80.0, SignalLevel.FAIR),
    Triple(-110.0, -95.0, SignalLevel.POOR), Triple(-Double.MAX_VALUE, -110.0, SignalLevel.BAD),
)
private val SINR_BANDS = listOf(
    Triple(20.0, Double.MAX_VALUE, SignalLevel.GOOD), Triple(13.0, 20.0, SignalLevel.FAIR),
    Triple(0.0, 13.0, SignalLevel.POOR), Triple(-Double.MAX_VALUE, 0.0, SignalLevel.BAD),
)

/**
 * RSRP and SINR over time (LTE and NR), and a timeline of network type, screen-off and speed tests. Values follow
 * flat.csv; missing values and missed ticks leave gaps (nothing is interpolated). Tap or drag to read one tick.
 */
@Composable
fun SessionCharts(data: ChartData, modifier: Modifier = Modifier) {
    var selected by rememberSaveable(data.ticks.size) { mutableStateOf<Int?>(null) }
    // Stable per data, so the gesture handlers are not restarted by every selection change.
    val select: (Double) -> Unit = remember(data) { { sec -> selected = data.nearest(sec) } }
    val rsrp = remember(data) { AxisRange.of(data.ticks.flatMap { listOfNotNull(it.lteRsrp, it.nrRsrp) }, -120.0..-70.0, 10.0) }
    val sinr = remember(data) { AxisRange.of(data.ticks.flatMap { listOfNotNull(it.lteSinr, it.nrSinr) }, -5.0..25.0, 5.0) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendItem(LTE_COLOR, "LTE")
            LegendItem(NR_COLOR, "NR")
            Text("tap or drag to read values", style = MaterialTheme.typography.labelSmall)
        }
        Text("RSRP / SS-RSRP (dBm)", style = MaterialTheme.typography.labelMedium)
        LineChart(data, rsrp, RSRP_BANDS, { it.lteRsrp }, { it.nrRsrp }, selected, select)
        Text("SINR / SS-SINR (dB)", style = MaterialTheme.typography.labelMedium)
        LineChart(data, sinr, SINR_BANDS, { it.lteSinr }, { it.nrSinr }, selected, select)
        Timeline(data, selected, select)
        Text(
            selected?.let { readout(data.ticks[it]) } ?: "–",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(12.dp, 3.dp)) { drawRect(color) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Plot area: left margin for the y labels, bottom margin for the time labels. */
private class Plot(val left: Float, val top: Float, val width: Float, val height: Float, val durationSec: Double, val range: AxisRange?) {
    fun x(sec: Double) = left + if (durationSec <= 0) 0f else (sec / durationSec * width).toFloat()
    fun sec(x: Float) = ((x - left) / width).coerceIn(0f, 1f) * durationSec
    fun y(v: Double): Float {
        val r = range ?: return top
        return top + ((r.max - v.coerceIn(r.min, r.max)) / (r.max - r.min) * height).toFloat()
    }
}

private class PlotHolder(var value: Plot = Plot(0f, 0f, 1f, 1f, 0.0, null))

private fun Modifier.selectByX(plot: () -> Plot, select: (Double) -> Unit) = this
    .pointerInput(select) { detectTapGestures { select(plot().sec(it.x)) } }
    .pointerInput(select) { detectHorizontalDragGestures { change, _ -> select(plot().sec(change.position.x)) } }

@Composable
private fun LineChart(
    data: ChartData,
    range: AxisRange,
    bands: List<Triple<Double, Double, SignalLevel>>,
    lte: (ChartTick) -> Int?,
    nr: (ChartTick) -> Int?,
    selected: Int?,
    select: (Double) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lteLines = remember(data) { data.lines(lte) }
    val nrLines = remember(data) { data.lines(nr) }
    // Plain holder, not state: the draw pass sets it, the gesture handlers read it (state would redraw forever).
    val plot = remember { PlotHolder() }
    Canvas(Modifier.fillMaxWidth().height(170.dp).selectByX({ plot.value }, select)) {
        val left = LABEL_WIDTH_DP.dp.toPx()
        val bottom = 16.dp.toPx()
        val p = Plot(left, 4.dp.toPx(), size.width - left, size.height - bottom - 4.dp.toPx(), data.durationSec, range)
        plot.value = p
        bands.forEach { (from, to, level) ->
            val yTop = p.y(minOf(to, range.max))
            val yBottom = p.y(maxOf(from, range.min))
            if (yBottom > yTop) drawRect(level.color.copy(alpha = BAND_ALPHA), Offset(p.left, yTop), Size(p.width, yBottom - yTop))
        }
        var v = range.min
        while (v <= range.max + 1e-9) {
            val y = p.y(v)
            drawLine(gridColor, Offset(p.left, y), Offset(p.left + p.width, y), strokeWidth = 1f)
            label(measurer, "%.0f".format(Locale.ROOT, v), Offset(0f, y - 6.sp.toPx()), labelColor)
            v += range.step
        }
        drawLines(lteLines, p, LTE_COLOR)
        drawLines(nrLines, p, NR_COLOR)
        timeLabels(measurer, data, p, size.height - bottom + 2.dp.toPx(), labelColor)
        selected?.let { drawCursor(p.x(data.ticks[it].tSec), p.top, p.top + p.height) }
    }
}

private fun DrawScope.drawLines(lines: List<List<Pair<Double, Int>>>, p: Plot, color: Color) {
    val stroke = Stroke(width = 1.5.dp.toPx())
    lines.forEach { line ->
        if (line.size == 1) {
            drawCircle(color, 1.5.dp.toPx(), Offset(p.x(line[0].first), p.y(line[0].second.toDouble())))
        } else {
            val path = Path()
            line.forEachIndexed { i, (t, v) ->
                val x = p.x(t)
                val y = p.y(v.toDouble())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = stroke)
        }
    }
}

private fun DrawScope.drawCursor(x: Float, top: Float, bottom: Float) =
    drawLine(Color.Gray, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())

private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, color: Color) =
    drawText(measurer, text, at, TextStyle(fontSize = 10.sp, color = color))

/** Wall-clock time at the start, middle and end of the session. */
private fun DrawScope.timeLabels(measurer: TextMeasurer, data: ChartData, p: Plot, y: Float, color: Color) {
    if (data.ticks.isEmpty()) return
    listOf(0.0, 0.5, 1.0).forEach { f ->
        val tick = data.ticks[data.nearest(f * data.durationSec) ?: return@forEach]
        val text = clock(tick.wallTimeMs)
        val w = measurer.measure(text, TextStyle(fontSize = 10.sp)).size.width
        val x = (p.x(tick.tSec) - w * f.toFloat()).coerceIn(p.left, p.left + p.width - w)
        label(measurer, text, Offset(x, y), color)
    }
}

/** Rows: network type, screen off, speed tests. */
@Composable
private fun Timeline(data: ChartData, selected: Int?, select: (Double) -> Unit) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val networks = remember(data) { data.networkTypes }
    val screenOff = remember(data) { data.screenOff }
    val plot = remember { PlotHolder() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(58.dp).selectByX({ plot.value }, select)) {
            val left = LABEL_WIDTH_DP.dp.toPx()
            val p = Plot(left, 0f, size.width - left, size.height, data.durationSec, null)
            plot.value = p
            val row = 14.dp.toPx()
            val gap = 4.dp.toPx()
            fun bar(rowIndex: Int, from: Double, to: Double, color: Color) {
                val x0 = p.x(from)
                drawRect(color, Offset(x0, rowIndex * (row + gap)), Size(maxOf(p.x(to) - x0, 1f), row))
            }
            label(measurer, "Net", Offset(0f, 0f), labelColor)
            label(measurer, "Scr off", Offset(0f, row + gap), labelColor)
            label(measurer, "Test", Offset(0f, 2 * (row + gap)), labelColor)
            networks.forEach { bar(0, it.startSec, it.endSec, NetworkColors.color(it.value)) }
            screenOff.forEach { bar(1, it.startSec, it.endSec, SCREEN_OFF_COLOR) }
            data.tests.forEach { bar(2, it.startSec, maxOf(it.endSec, it.startSec + 1), if (it.error == null) TEST_COLOR else SignalLevel.BAD.color) }
            selected?.let { drawCursor(p.x(data.ticks[it].tSec), 0f, size.height) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.width(LABEL_WIDTH_DP.dp))
            networks.map { it.value }.distinct().forEach { LegendItem(NetworkColors.color(it), it) }
        }
    }
}

private fun clock(wallTimeMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(wallTimeMs))

/** One line per selected tick; "(SS)" marks a SignalStrength value, as on the map. */
internal fun readout(t: ChartTick, time: (Long) -> String = ::clock): String {
    fun v(value: Int?, source: String?) = value?.let { "$it" + if (source == "SIGNAL_STRENGTH") " (SS)" else "" } ?: "–"
    return "${time(t.wallTimeMs)} #${t.seq} ${t.networkType}" + (if (t.screenOn == false) " screen off" else "") + "\n" +
        "RSRP LTE ${v(t.lteRsrp, t.lteRsrpSource)} · NR ${v(t.nrRsrp, t.nrRsrpSource)}\n" +
        "SINR LTE ${v(t.lteSinr, t.lteSinrSource)} · NR ${v(t.nrSinr, t.nrSinrSource)}"
}
