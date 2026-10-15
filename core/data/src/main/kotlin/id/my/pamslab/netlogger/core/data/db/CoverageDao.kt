package id.my.pamslab.netlogger.core.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * Per-field counts over the serving cells of one RAT (servingInferred, any source): [ticks] with such a cell, and
 * for each field the ticks where at least one of them had a value.
 */
data class RatCoverageRow(
    val ticks: Long,
    val rsrp: Long,
    val rsrq: Long,
    val sinr: Long,
    val rssi: Long,
    val cqi: Long,
    val ta: Long,
    val csiRsrp: Long,
    val pci: Long,
    val arfcn: Long,
    val band: Long,
    val bandApi: Long,
    val cellId: Long,
    val tac: Long,
)

/** Read-only counts for Settings › Phone capabilities ("seen in recorded sessions", decision 003, D3). */
@Dao
interface CoverageDao {
    @Query("SELECT COUNT(*) FROM session")
    suspend fun sessions(): Long

    @Query("SELECT COUNT(*) FROM measurement")
    suspend fun ticks(): Long

    @Query(
        "SELECT COUNT(*) AS ticks, IFNULL(SUM(rsrp), 0) AS rsrp, IFNULL(SUM(rsrq), 0) AS rsrq, IFNULL(SUM(sinr), 0) AS sinr, " +
            "IFNULL(SUM(rssi), 0) AS rssi, IFNULL(SUM(cqi), 0) AS cqi, IFNULL(SUM(ta), 0) AS ta, " +
            "IFNULL(SUM(csiRsrp), 0) AS csiRsrp, IFNULL(SUM(pci), 0) AS pci, IFNULL(SUM(arfcn), 0) AS arfcn, " +
            "IFNULL(SUM(band), 0) AS band, IFNULL(SUM(bandApi), 0) AS bandApi, IFNULL(SUM(cellId), 0) AS cellId, " +
            "IFNULL(SUM(tac), 0) AS tac FROM (" +
            "SELECT MAX(rsrp IS NOT NULL) AS rsrp, MAX(rsrq IS NOT NULL) AS rsrq, MAX(sinr IS NOT NULL) AS sinr, " +
            "MAX(rssi IS NOT NULL) AS rssi, MAX(cqi IS NOT NULL) AS cqi, MAX(ta IS NOT NULL) AS ta, " +
            "MAX(csiRsrp IS NOT NULL) AS csiRsrp, MAX(pci IS NOT NULL) AS pci, MAX(arfcn IS NOT NULL) AS arfcn, " +
            "MAX(band IS NOT NULL) AS band, MAX(bandSource = 'API') AS bandApi, MAX(ciOrNci IS NOT NULL) AS cellId, " +
            "MAX(tac IS NOT NULL) AS tac FROM cell WHERE rat = :rat AND servingInferred = 1 GROUP BY measurementId)",
    )
    suspend fun servingRat(rat: String): RatCoverageRow

    /** Ticks with a serving cell of [rat] from [source] (CELL_INFO / SIGNAL_STRENGTH). */
    @Query("SELECT COUNT(DISTINCT measurementId) FROM cell WHERE rat = :rat AND source = :source AND servingInferred = 1")
    suspend fun servingTicks(rat: String, source: String): Long

    /** Ticks with at least one neighbour (not serving) cell from CellInfo. */
    @Query("SELECT COUNT(DISTINCT measurementId) FROM cell WHERE servingInferred = 0 AND source = 'CELL_INFO'")
    suspend fun neighborTicks(): Long

    /** Ticks with cell bandwidths from ServiceState ([minEntries] 1: any, 2: a carrier aggregation hint). */
    @Query(
        "SELECT COUNT(*) FROM measurement WHERE cellBandwidthsJson IS NOT NULL AND cellBandwidthsJson != '[]' " +
            "AND (:minEntries < 2 OR cellBandwidthsJson LIKE '%,%')",
    )
    suspend fun bandwidthTicks(minEntries: Int): Long
}
