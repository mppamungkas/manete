package id.my.pamslab.netlogger.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query

/** A cell row with the tick it belongs to, so cells.csv can carry the tick's sequence number and timestamps. */
data class CellWithTick(
    val seq: Long,
    val wallTimeMs: Long,
    val elapsedRealtimeNs: Long,
    @Embedded val cell: CellEntity,
)

/** A warning row with the sequence number of the tick it was first seen in. */
data class WarningWithSeq(
    val firstSeq: Long?,
    @Embedded val warning: ParseWarningEntity,
)

/** Read-only, paged queries for export. Pages are by tick sequence number, so memory stays flat for long sessions. */
@Dao
interface ExportDao {

    @Query("SELECT * FROM session WHERE id = :sessionId")
    suspend fun session(sessionId: Long): SessionEntity?

    @Query("SELECT * FROM measurement WHERE sessionId = :sessionId AND seq > :afterSeq ORDER BY seq LIMIT :limit")
    suspend fun measurements(sessionId: Long, afterSeq: Long, limit: Int): List<MeasurementEntity>

    @Query(
        "SELECT m.seq, m.wallTimeMs, m.elapsedRealtimeNs, c.* FROM cell c JOIN measurement m ON c.measurementId = m.id " +
            "WHERE m.sessionId = :sessionId AND m.seq BETWEEN :fromSeq AND :toSeq ORDER BY m.seq, c.id",
    )
    suspend fun cells(sessionId: Long, fromSeq: Long, toSeq: Long): List<CellWithTick>

    @Query("SELECT * FROM event WHERE sessionId = :sessionId ORDER BY elapsedRealtimeNs, id")
    suspend fun events(sessionId: Long): List<EventEntity>

    @Query(
        "SELECT m.seq AS firstSeq, w.* FROM parse_warning w LEFT JOIN measurement m ON w.measurementId = m.id " +
            "WHERE w.sessionId = :sessionId ORDER BY w.id",
    )
    suspend fun warnings(sessionId: Long): List<WarningWithSeq>

    @Query("SELECT COUNT(*) FROM measurement WHERE sessionId = :sessionId")
    suspend fun measurementCount(sessionId: Long): Long
}
