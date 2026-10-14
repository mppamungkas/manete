package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.data.db.CellEntity
import id.my.pamslab.netlogger.core.data.db.CellWithTick
import id.my.pamslab.netlogger.core.data.db.EventEntity
import id.my.pamslab.netlogger.core.data.db.ExportDao
import id.my.pamslab.netlogger.core.data.db.MeasurementEntity
import id.my.pamslab.netlogger.core.data.db.SessionEntity
import id.my.pamslab.netlogger.core.data.db.WarningWithSeq
import kotlinx.coroutines.flow.MutableStateFlow

const val T0 = 1_791_931_200_000L

fun tick(seq: Long, lat: Double? = -0.9 + seq * 1e-5, lon: Double? = 100.4, networkType: String = "NR_NSA") = MeasurementEntity(
    id = seq + 1, sessionId = 1, seq = seq, wallTimeMs = T0 + seq * 1_000, elapsedRealtimeNs = seq * 1_000_000_000,
    lat = lat, lon = lon, altitude = null, speedMps = 1f, bearing = null, gpsAccuracy = 4.7f, locationAgeMs = 300,
    locationStatus = "OK", networkType = networkType, overrideNetworkType = 3, dataNetworkType = 13, nsaLteServing = true,
    nsaNrCellInfo = true, nsaNrSignal = true, serviceState = 0, dataState = 2, isRoaming = false, operatorNumeric = "51089",
    cellInfoOrigin = "FRESH", cellBandwidthsJson = "[]", batteryLevel = null, batteryCurrentUa = null, batteryVoltageMv = null,
    batteryTempC = null, isCharging = null, rxBytesTotal = null, txBytesTotal = null, rxBytesApp = null, txBytesApp = null,
    screenOn = true, thermalStatus = null, parseErrors = 0, warningCount = 0,
)

fun cell(rat: String, source: String = "CELL_INFO", serving: Boolean = true, pci: Int? = null, rsrp: Int? = null, sinr: Int? = null, servingSource: String? = null) = CellEntity(
    measurementId = 0, rat = rat, servingInferred = serving, reportedServing = if (source == "CELL_INFO") serving else null,
    servingSource = servingSource, isRegistered = if (rat == "LTE" && source == "CELL_INFO") serving else false,
    connectionStatus = null, ciOrNci = null, tac = null, pci = pci, arfcn = null, band = null, bandSource = null,
    bandwidthKhz = null, mcc = null, mnc = null, rsrpDbm = rsrp, rsrqDb = null, sinrDb = sinr, rssiDbm = null, cqi = null,
    timingAdvance = null, csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null, ecNoDb = null,
    source = source, cellInfoAgeMs = 10,
)

/** Recording session: [add] inserts a tick and moves observeLastSeq like Room's invalidation would. */
class LiveDao(ticks: List<MeasurementEntity>, private val cells: (Long) -> List<CellEntity>) : ExportDao {
    val stored = ticks.toMutableList()
    val events = mutableListOf<EventEntity>()
    private val lastSeq = MutableStateFlow(ticks.lastOrNull()?.seq)
    var measurementQueries = 0

    fun add(m: MeasurementEntity) {
        stored += m
        lastSeq.value = m.seq
    }

    override suspend fun session(sessionId: Long): SessionEntity? = null
    override suspend fun measurements(sessionId: Long, afterSeq: Long, limit: Int): List<MeasurementEntity> {
        measurementQueries++
        return stored.filter { it.seq > afterSeq }.take(limit)
    }
    override suspend fun cells(sessionId: Long, fromSeq: Long, toSeq: Long) =
        stored.filter { it.seq in fromSeq..toSeq }.flatMap { m -> cells(m.seq).map { CellWithTick(m.seq, m.wallTimeMs, m.elapsedRealtimeNs, it) } }
    override suspend fun events(sessionId: Long) = events.toList()
    override suspend fun warnings(sessionId: Long) = emptyList<WarningWithSeq>()
    override suspend fun measurementCount(sessionId: Long) = stored.size.toLong()
    override fun observeLastSeq(sessionId: Long) = lastSeq
}
