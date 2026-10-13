package id.my.pamslab.netlogger.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** Adds occurrences to an existing warning row. */
data class WarningBump(val id: Long, val count: Long, val lastSeenMs: Long, val lastRawValue: String?)

@Dao
abstract class MeasurementDao {

    @Insert
    abstract suspend fun insertMeasurement(measurement: MeasurementEntity): Long

    @Insert
    abstract suspend fun insertCells(cells: List<CellEntity>)

    @Insert
    abstract suspend fun insertWarning(warning: ParseWarningEntity): Long

    @Query(
        "UPDATE parse_warning SET occurrences = occurrences + :count, lastSeenMs = :lastSeenMs, " +
            "lastRawValue = :lastRawValue WHERE id = :id",
    )
    abstract suspend fun bumpWarning(id: Long, count: Long, lastSeenMs: Long, lastRawValue: String?)

    @Insert
    abstract suspend fun insertEvent(event: EventEntity): Long

    /**
     * One tick in one transaction: the measurement, its cells, new warning rows (their measurementId is set here)
     * and counter updates for warnings already seen in the session. Returns the measurement id and the new warning
     * ids in the order of [newWarnings].
     */
    @Transaction
    open suspend fun insertTick(
        measurement: MeasurementEntity,
        cells: List<CellEntity>,
        newWarnings: List<ParseWarningEntity>,
        bumps: List<WarningBump>,
    ): Pair<Long, List<Long>> {
        val measurementId = insertMeasurement(measurement)
        insertCells(cells.map { it.copy(measurementId = measurementId) })
        val ids = newWarnings.map { insertWarning(it.copy(measurementId = measurementId)) }
        bumps.forEach { bumpWarning(it.id, it.count, it.lastSeenMs, it.lastRawValue) }
        return measurementId to ids
    }

    @Query("SELECT * FROM measurement WHERE sessionId = :sessionId ORDER BY seq")
    abstract suspend fun measurements(sessionId: Long): List<MeasurementEntity>

    @Query("SELECT * FROM cell WHERE measurementId = :measurementId ORDER BY id")
    abstract suspend fun cells(measurementId: Long): List<CellEntity>

    @Query("SELECT * FROM parse_warning WHERE sessionId = :sessionId ORDER BY id")
    abstract suspend fun warnings(sessionId: Long): List<ParseWarningEntity>

    @Query("SELECT * FROM event WHERE sessionId = :sessionId ORDER BY elapsedRealtimeNs")
    abstract suspend fun events(sessionId: Long): List<EventEntity>
}
