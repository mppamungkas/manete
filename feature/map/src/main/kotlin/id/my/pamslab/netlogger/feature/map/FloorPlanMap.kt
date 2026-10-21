package id.my.pamslab.netlogger.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import id.my.pamslab.netlogger.core.ui.plan.PlanView

/**
 * A session on its floor plan (decision 006, step 4): the walked path (taps joined in time order), the ticks placed
 * between taps coloured by [metric], the selected tick ringed. A tap selects the nearest tick within a finger's reach.
 */
@Composable
internal fun FloorPlanMap(
    track: PlanTrack,
    metric: Metric,
    selectedSeq: Long?,
    onPointClick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = track.plan.plan
    val colors = remember(track, metric) { track.points.map { Color(metric.argb(it.values)) } }
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        PlanView(
            track.plan.image, plan.widthPx, plan.heightPx, Modifier.fillMaxSize(),
            onTap = { x, y, scale -> onPointClick(nearestPoint(track.points, x, y, TOUCH_RADIUS_PX / scale)?.values?.seq) },
        ) { v ->
            if (track.path.size > 1) {
                val line = Path().apply {
                    track.path.forEachIndexed { i, w ->
                        val (sx, sy) = v.toScreen(w.x.toFloat(), w.y.toFloat())
                        if (i == 0) moveTo(sx, sy) else lineTo(sx, sy)
                    }
                }
                drawPath(line, Color.White, style = Stroke(width = 7f))
                drawPath(line, PATH_COLOR, style = Stroke(width = 3f))
            }
            track.points.forEachIndexed { i, p ->
                val (sx, sy) = v.toScreen(p.position.x.toFloat(), p.position.y.toFloat())
                drawCircle(Color.White, radius = DOT_RADIUS + 1.5f, center = Offset(sx, sy))
                drawCircle(colors[i], radius = DOT_RADIUS, center = Offset(sx, sy))
            }
            track.path.forEach { w ->
                val (sx, sy) = v.toScreen(w.x.toFloat(), w.y.toFloat())
                drawCircle(PATH_COLOR, radius = 5f, center = Offset(sx, sy))
            }
            track.points.firstOrNull { it.values.seq == selectedSeq }?.let { p ->
                val (sx, sy) = v.toScreen(p.position.x.toFloat(), p.position.y.toFloat())
                drawCircle(Color.White, radius = 18f, center = Offset(sx, sy), style = Stroke(width = 8f))
                drawCircle(Color.Black, radius = 18f, center = Offset(sx, sy), style = Stroke(width = 4f))
            }
        }
    }
}

private const val DOT_RADIUS = 7f

/** How far from a dot a tap still selects it, in screen pixels (about a fingertip). */
private const val TOUCH_RADIUS_PX = 48f

/** The walked path: as on Live › Plan. */
private val PATH_COLOR = Color(0xFF37474F)
