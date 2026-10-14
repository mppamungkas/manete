package id.my.pamslab.netlogger.feature.sessions.charts

import id.my.pamslab.netlogger.core.data.db.ExportDao
import id.my.pamslab.netlogger.core.export.FlatRow
import javax.inject.Inject

/** Reads a session's ticks in pages, like the export, and reduces them to chart values. */
class ChartLoader @Inject constructor(private val dao: ExportDao) {

    suspend fun load(sessionId: Long): ChartData? {
        val session = dao.session(sessionId) ?: return null
        val ticks = mutableListOf<ChartTick>()
        var t0: Long? = null
        var after = -1L
        while (true) {
            val page = dao.measurements(sessionId, after, PAGE_TICKS)
            if (page.isEmpty()) break
            val start = t0 ?: page.first().elapsedRealtimeNs.also { t0 = it }
            val cells = dao.cells(sessionId, page.first().seq, page.last().seq).groupBy({ it.seq }, { it.cell })
            // Reduced page by page: only the chart values stay in memory, not the cells.
            page.mapTo(ticks) { ChartData.tick(FlatRow.of(it, cells[it.seq].orEmpty()), start) }
            after = page.last().seq
        }
        return ChartData(ticks, session.intervalMs, ChartData.tests(dao.throughputTests(sessionId), t0 ?: 0L))
    }

    private companion object {
        const val PAGE_TICKS = 500
    }
}
