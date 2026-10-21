package id.my.pamslab.netlogger.core.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel
import java.io.File
import java.io.OutputStream
import javax.inject.Inject

/** One placed tick for a heatmap: plan position (stored-image pixels) and the serving values as in flat.csv. */
data class HeatmapPoint(val x: Double, val y: Double, val lteRsrp: Int?, val nrRsrp: Int?, val lteSinr: Int?, val nrSinr: Int?)

/** What a heatmap colours by, with the Map's colour rules (CLAUDE.md). */
enum class HeatmapMetric(val fileSuffix: String, val label: String, val value: (HeatmapPoint) -> Int?, val level: (Int?) -> SignalLevel) {
    LTE_RSRP("lte_rsrp", "LTE RSRP", { it.lteRsrp }, SignalLevel::rsrp),
    NR_RSRP("nr_rsrp", "NR SS-RSRP", { it.nrRsrp }, SignalLevel::rsrp),
    LTE_SINR("lte_sinr", "LTE SINR", { it.lteSinr }, SignalLevel::sinr),
    NR_SINR("nr_sinr", "NR SS-SINR", { it.nrSinr }, SignalLevel::sinr),
    ;

    /** Legend rows: level and text. */
    val legend: List<Pair<SignalLevel, String>>
        get() = when (this) {
            LTE_RSRP, NR_RSRP -> listOf(
                SignalLevel.GOOD to "> -80 dBm", SignalLevel.FAIR to "-80 … -95", SignalLevel.POOR to "-96 … -110",
                SignalLevel.BAD to "< -110", SignalLevel.NONE to "none",
            )
            LTE_SINR, NR_SINR -> listOf(
                SignalLevel.GOOD to "≥ 20 dB", SignalLevel.FAIR to "13 … 19", SignalLevel.POOR to "0 … 12",
                SignalLevel.BAD to "< 0", SignalLevel.NONE to "none",
            )
        }

    companion object {
        /** The metrics worth a heatmap for [points]: those with at least one value. */
        fun withValues(points: List<HeatmapPoint>): List<HeatmapMetric> = entries.filter { m -> points.any { m.value(it) != null } }
    }
}

/** Draws a floor plan with its placed ticks (decision 006 step 7); Android graphics, so tests replace it. */
interface HeatmapRenderer {
    /**
     * Writes a PNG of [image] (a plan of [widthPx] × [heightPx]) with the walked [path] and [points] coloured by
     * [metric], plus a legend titled [title]. False if the image cannot be read or drawn.
     */
    fun render(
        image: File,
        widthPx: Int,
        heightPx: Int,
        title: String,
        metric: HeatmapMetric,
        points: List<HeatmapPoint>,
        path: List<Pair<Double, Double>>,
        out: OutputStream,
    ): Boolean
}

class AndroidHeatmapRenderer @Inject constructor() : HeatmapRenderer {

    override fun render(
        image: File,
        widthPx: Int,
        heightPx: Int,
        title: String,
        metric: HeatmapMetric,
        points: List<HeatmapPoint>,
        path: List<Pair<Double, Double>>,
        out: OutputStream,
    ): Boolean = try {
        draw(image, widthPx, heightPx, title, metric, points, path)?.let { bmp ->
            val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            ok
        } ?: false
    } catch (e: Exception) {
        CaughtErrors.caught("heatmap not drawn", e)
        false
    } catch (e: OutOfMemoryError) {
        CaughtErrors.record("heatmap too large", e)
        false
    }

    private fun draw(
        image: File,
        widthPx: Int,
        heightPx: Int,
        title: String,
        metric: HeatmapMetric,
        points: List<HeatmapPoint>,
        path: List<Pair<Double, Double>>,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val plan = BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
            ?: return null
        val bmp = if (plan.isMutable && plan.config == Bitmap.Config.ARGB_8888) plan else plan.copy(Bitmap.Config.ARGB_8888, true).also { plan.recycle() }
        val canvas = Canvas(bmp)
        val k = bmp.width.toFloat() / widthPx // plan pixels → this bitmap
        val unit = maxOf(bmp.width, bmp.height) / 400f // sizes scale with the image
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (path.size > 1) {
            val line = Path().apply {
                path.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x.toFloat() * k, y.toFloat() * k) else lineTo(x.toFloat() * k, y.toFloat() * k) }
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = unit * 1.6f
            paint.color = WHITE
            canvas.drawPath(line, paint)
            paint.strokeWidth = unit * 0.7f
            paint.color = PATH
            canvas.drawPath(line, paint)
        }
        paint.style = Paint.Style.FILL
        points.forEach { p ->
            val cx = p.x.toFloat() * k
            val cy = p.y.toFloat() * k
            paint.color = WHITE
            canvas.drawCircle(cx, cy, unit * 1.9f, paint)
            paint.color = metric.level(metric.value(p)).argb.toInt()
            canvas.drawCircle(cx, cy, unit * 1.5f, paint)
        }
        legend(canvas, unit, title, metric, points)
        return bmp
    }

    /** Title and colour rows with their tick counts, top-left on a white box. */
    private fun legend(canvas: Canvas, unit: Float, title: String, metric: HeatmapMetric, points: List<HeatmapPoint>) {
        val counts = points.groupingBy { metric.level(metric.value(it)) }.eachCount()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLACK; textSize = unit * 6f }
        val rows = listOf(title) + metric.legend.map { (level, label) -> "$label  ${counts[level] ?: 0}" }
        val pad = unit * 2.5f
        val lineH = text.textSize * 1.35f
        val swatch = text.textSize * 0.8f
        val width = rows.maxOf { text.measureText(it) } + swatch * 1.6f + pad * 2
        val box = Paint().apply { color = 0xE6FFFFFF.toInt() }
        canvas.drawRect(pad, pad, pad + width, pad + lineH * rows.size + pad, box)
        var y = pad + pad + text.textSize
        canvas.drawText(title, pad * 2, y, text)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        metric.legend.forEach { (level, label) ->
            y += lineH
            fill.color = level.argb.toInt()
            canvas.drawCircle(pad * 2 + swatch / 2, y - swatch / 2.5f, swatch / 2, fill)
            canvas.drawText("$label  ${counts[level] ?: 0}", pad * 2 + swatch * 1.6f, y, text)
        }
    }

    private companion object {
        /** Heatmaps are for reports: half the stored plan's maximum is plenty and keeps memory low. */
        const val MAX_SIDE = 2048
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val PATH = 0xFF37474F.toInt()
    }
}
