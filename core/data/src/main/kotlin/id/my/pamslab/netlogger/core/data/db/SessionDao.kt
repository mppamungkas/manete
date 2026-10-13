package id.my.pamslab.netlogger.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Session list row with counts. */
data class SessionSummaryRow(
    val id: Long,
    val uuid: String,
    val label: String?,
    val scenario: String?,
    val notes: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val endReason: String?,
    val intervalMs: Long,
    val deviceModel: String,
    val operatorName: String?,
    val measurements: Long,
    val cells: Long,
    val markers: Long,
    val warnings: Long,
)

data class MeasurementTime(val wallTimeMs: Long, val elapsedRealtimeNs: Long)

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query(
        "UPDATE session SET subscriptionId = :subscriptionId, simSlot = :simSlot, operatorName = :operatorName, " +
            "mcc = :mcc, mnc = :mnc WHERE id = :id",
    )
    suspend fun updateNetwork(id: Long, subscriptionId: Int?, simSlot: Int?, operatorName: String?, mcc: String?, mnc: String?)

    @Query("UPDATE session SET endedAt = :endedAt, endedElapsedNs = :endedElapsedNs, endReason = :endReason WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long, endedElapsedNs: Long?, endReason: String)

    @Query("SELECT * FROM session WHERE endedAt IS NULL")
    suspend fun openSessions(): List<SessionEntity>

    @Query("SELECT wallTimeMs, elapsedRealtimeNs FROM measurement WHERE sessionId = :sessionId ORDER BY seq DESC LIMIT 1")
    suspend fun lastMeasurement(sessionId: Long): MeasurementTime?

    @Query("UPDATE session SET label = :label, notes = :notes, scenario = :scenario WHERE id = :id")
    suspend fun updateDetails(id: Long, label: String?, notes: String?, scenario: String?)

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT scenario FROM session ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestScenario(): String?

    @Query(SUMMARY_SELECT + " ORDER BY s.startedAt DESC")
    fun observeSummaries(): Flow<List<SessionSummaryRow>>

    @Query(SUMMARY_SELECT + " WHERE s.id = :id")
    fun observeSummary(id: Long): Flow<SessionSummaryRow?>

    private companion object {
        const val SUMMARY_SELECT =
            "SELECT s.id, s.uuid, s.label, s.scenario, s.notes, s.startedAt, s.endedAt, s.endReason, s.intervalMs, " +
                "s.deviceModel, s.operatorName, " +
                "(SELECT COUNT(*) FROM measurement m WHERE m.sessionId = s.id) AS measurements, " +
                "(SELECT COUNT(*) FROM cell c JOIN measurement m ON c.measurementId = m.id WHERE m.sessionId = s.id) AS cells, " +
                "(SELECT COUNT(*) FROM event e WHERE e.sessionId = s.id AND e.type = 'USER_MARKER') AS markers, " +
                "(SELECT COUNT(*) FROM parse_warning w WHERE w.sessionId = s.id) AS warnings " +
                "FROM session s"
    }
}
