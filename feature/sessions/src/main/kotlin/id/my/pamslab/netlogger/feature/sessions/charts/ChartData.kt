package id.my.pamslab.netlogger.feature.sessions.charts

import id.my.pamslab.netlogger.core.data.db.ThroughputTestEntity
import id.my.pamslab.netlogger.core.export.FlatRow
import kotlin.math.ceil
import kotlin.math.floor

/** One tick as the charts show it. Values follow flat.csv (CELL_INFO first, else a current SIGNAL_STRENGTH value). */
data class ChartTick(
    val seq: Long,
    /** Seconds since the first tick, from elapsedRealtimeNs (immune to clock changes). */
    val tSec: Double,
    val wallTimeMs: Long,
    val networkType: String,
    val screenOn: Boolean?,
    val lteRsrp: Int?,
    val lteRsrpSource: String?,
    val nrRsrp: Int?,
    val nrRsrpSource: String?,
    val lteSinr: Int?,
    val lteSinrSource: String?,
    val nrSinr: Int?,
    val nrSinrSource: String?,
)

/** A run of consecutive ticks with the same value, for timelines. */
data class Segment<T>(val startSec: Double, val endSec: Double, val value: T)

/** A speed test phase placed on the time axis. */
data class ChartTest(val startSec: Double, val endSec: Double, val type: String, val mbps: Double?, val error: String?)

data class ChartData(val ticks: List<ChartTick>, val intervalMs: Long, val tests: List<ChartTest> = emptyList()) {

    val durationSec: Double get() = ticks.lastOrNull()?.tSec ?: 0.0

    /** Network type runs; a tick lasts until the next one (the last one for one interval). */
    val networkTypes: List<Segment<String>> get() = runs { it.networkType }

    /** Screen-off runs only (screen on or unknown is not drawn). */
    val screenOff: List<Segment<Boolean>> get() = runs { it.screenOn == false }.filter { it.value }

    private fun <T> runs(key: (ChartTick) -> T): List<Segment<T>> {
        val out = mutableListOf<Segment<T>>()
        var start = 0
        for (i in 1..ticks.size) {
            if (i == ticks.size || key(ticks[i]) != key(ticks[start])) {
                val end = if (i < ticks.size) ticks[i].tSec else ticks[i - 1].tSec + intervalMs / 1000.0
                out += Segment(ticks[start].tSec, end, key(ticks[start]))
                start = i
            }
        }
        return out
    }

    /**
     * Line pieces of one series: consecutive ticks with a value. A missing value or a gap longer than
     * [GAP_INTERVALS] intervals (missed ticks) breaks the line, so nothing is interpolated.
     */
    fun lines(value: (ChartTick) -> Int?): List<List<Pair<Double, Int>>> {
        val out = mutableListOf<List<Pair<Double, Int>>>()
        var current = mutableListOf<Pair<Double, Int>>()
        var lastT: Double? = null
        for (t in ticks) {
            val v = value(t)
            val gap = lastT != null && t.tSec - lastT > GAP_INTERVALS * intervalMs / 1000.0
            if (v == null || gap) {
                if (current.isNotEmpty()) out += current
                current = mutableListOf()
            }
            if (v != null) current += t.tSec to v
            lastT = t.tSec
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** Index of the tick closest to [sec]. */
    fun nearest(sec: Double): Int? {
        if (ticks.isEmpty()) return null
        var lo = 0
        var hi = ticks.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (ticks[mid].tSec < sec) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && sec - ticks[lo - 1].tSec < ticks[lo].tSec - sec) lo - 1 else lo
    }

    companion object {
        const val GAP_INTERVALS = 2.5

        fun of(rows: List<FlatRow>, intervalMs: Long, tests: List<ThroughputTestEntity> = emptyList()): ChartData {
            val t0 = rows.firstOrNull()?.m?.elapsedRealtimeNs ?: 0L
            return ChartData(rows.map { tick(it, t0) }, intervalMs, tests(tests, t0))
        }

        /** [t0Ns]: elapsedRealtimeNs of the session's first tick. */
        fun tick(row: FlatRow, t0Ns: Long): ChartTick {
            val m = row.m
            val lteRsrp = row.pick(row.lte, row.lteSignal) { it.rsrpDbm }
            val nrRsrp = row.pick(row.nr, row.nrSignal) { it.rsrpDbm }
            val lteSinr = row.pick(row.lte, row.lteSignal) { it.sinrDb }
            val nrSinr = row.pick(row.nr, row.nrSignal) { it.sinrDb }
            return ChartTick(
                seq = m.seq, tSec = (m.elapsedRealtimeNs - t0Ns) / 1e9, wallTimeMs = m.wallTimeMs,
                networkType = m.networkType, screenOn = m.screenOn,
                lteRsrp = lteRsrp.value, lteRsrpSource = lteRsrp.source, nrRsrp = nrRsrp.value, nrRsrpSource = nrRsrp.source,
                lteSinr = lteSinr.value, lteSinrSource = lteSinr.source, nrSinr = nrSinr.value, nrSinrSource = nrSinr.source,
            )
        }

        fun tests(tests: List<ThroughputTestEntity>, t0Ns: Long): List<ChartTest> = tests.map {
            val start = (it.startedElapsedNs - t0Ns) / 1e9
            ChartTest(start, start + (it.durationMs ?: 0) / 1000.0, it.type, it.mbps, it.error)
        }
    }
}

/** Y axis range: the data range, at least [default], rounded out to [step]. */
data class AxisRange(val min: Double, val max: Double, val step: Double) {
    companion object {
        fun of(values: List<Int>, default: ClosedRange<Double>, step: Double): AxisRange {
            val lo = minOf(values.minOrNull()?.toDouble() ?: default.start, default.start)
            val hi = maxOf(values.maxOrNull()?.toDouble() ?: default.endInclusive, default.endInclusive)
            return AxisRange(floor(lo / step) * step, ceil(hi / step) * step, step)
        }
    }
}
