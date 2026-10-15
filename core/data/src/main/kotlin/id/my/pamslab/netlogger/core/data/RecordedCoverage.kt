package id.my.pamslab.netlogger.core.data

import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import id.my.pamslab.netlogger.core.data.db.RatCoverageRow
import javax.inject.Inject

/** How often each field had a value in this phone's recorded sessions (Phone capabilities, decision 003, D3). */
data class FieldCoverage(
    val sessions: Long,
    val ticks: Long,
    /** Serving LTE cells, any source. */
    val lte: RatCoverageRow,
    /** Serving NR cells, any source (CellInfo or SignalStrength). */
    val nr: RatCoverageRow,
    val nrCellInfoTicks: Long,
    val nrSignalStrengthTicks: Long,
    val bandwidthTicks: Long,
    val caHintTicks: Long,
    val neighborTicks: Long,
)

interface RecordedCoverage {
    /** Counts over all sessions in the database; a full scan of the cell table, so call it off the main thread. */
    suspend fun coverage(): FieldCoverage
}

class RoomRecordedCoverage @Inject constructor(private val db: NetLoggerDatabase) : RecordedCoverage {
    override suspend fun coverage(): FieldCoverage {
        val dao = db.coverageDao()
        return FieldCoverage(
            sessions = dao.sessions(),
            ticks = dao.ticks(),
            lte = dao.servingRat("LTE"),
            nr = dao.servingRat("NR"),
            nrCellInfoTicks = dao.servingTicks("NR", "CELL_INFO"),
            nrSignalStrengthTicks = dao.servingTicks("NR", "SIGNAL_STRENGTH"),
            bandwidthTicks = dao.bandwidthTicks(1),
            caHintTicks = dao.bandwidthTicks(2),
            neighborTicks = dao.neighborTicks(),
        )
    }
}
