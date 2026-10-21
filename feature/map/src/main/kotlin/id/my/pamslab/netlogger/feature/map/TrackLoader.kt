package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.data.db.ExportDao
import id.my.pamslab.netlogger.core.export.FlatRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Loads a session's track and keeps it current: the full track first, then only the ticks added since (a recording
 * session grows live; a stopped one emits once). Ticks are read in pages, like the export.
 */
class TrackLoader @Inject constructor(private val dao: ExportDao) {

    fun track(sessionId: Long): Flow<Track> = flow {
        val points = mutableListOf<TrackPoint>()
        val values = mutableListOf<TickValues>()
        var lastSeq = -1L
        var ticks = 0L
        dao.observeLastSeq(sessionId).distinctUntilChanged().collect { newest ->
            if (newest != null && newest > lastSeq) {
                while (true) {
                    val page = dao.measurements(sessionId, lastSeq, PAGE_TICKS)
                    if (page.isEmpty()) break
                    val cells = dao.cells(sessionId, page.first().seq, page.last().seq).groupBy({ it.seq }, { it.cell })
                    page.forEach { m ->
                        val row = FlatRow.of(m, cells[m.seq].orEmpty())
                        val v = TickValues.of(row)
                        values += v
                        TrackPoint.of(row)?.let { points += it.copy(values = v) }
                    }
                    ticks += page.size
                    lastSeq = page.last().seq
                }
            }
            emit(Track(sessionId, points.toList(), dao.events(sessionId), ticks, values.toList()))
        }
    }

    private companion object {
        const val PAGE_TICKS = 500
    }
}
