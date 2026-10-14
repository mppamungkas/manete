package id.my.pamslab.netlogger.core.export

import id.my.pamslab.netlogger.core.data.db.CellEntity
import id.my.pamslab.netlogger.core.data.db.CellWithTick
import id.my.pamslab.netlogger.core.data.db.EventEntity
import id.my.pamslab.netlogger.core.data.db.ExportDao
import id.my.pamslab.netlogger.core.data.db.MeasurementEntity
import id.my.pamslab.netlogger.core.data.db.ParseWarningEntity
import id.my.pamslab.netlogger.core.data.db.SessionEntity
import id.my.pamslab.netlogger.core.data.db.WarningWithSeq

const val START_MS = 1_791_931_200_000L // 2026-10-14T05:40:00+07:00
const val START_NS = 580_000_000_000_000L

fun session(endedAt: Long? = START_MS + 60_000, label: String? = "Pasar Raya, loop 1 \"A\"") = SessionEntity(
    id = 1, uuid = "cb1784a9-879e-4a6e-9959-31f7b7d9821c", startedAt = START_MS, startedElapsedNs = START_NS,
    endedAt = endedAt, endedElapsedNs = endedAt?.let { START_NS + (it - START_MS) * 1_000_000 },
    endReason = endedAt?.let { "USER_STOP" }, label = label, scenario = "WALK", notes = "Ümlaut, comma\nsecond line",
    intervalMs = 1_000, deviceManufacturer = "samsung", deviceModel = "SM-A566B", androidVersion = "16",
    basebandVersion = "A566BXXU", appVersion = "0.1.0", schemaVersion = 1, subscriptionId = 5, simSlot = 0,
    operatorName = "3", mcc = "510", mnc = "89",
)

fun measurement(seq: Long, lat: Double? = -0.9 - seq * 1e-5, lon: Double? = 100.4 + seq * 1e-5) = MeasurementEntity(
    id = seq + 1, sessionId = 1, seq = seq, wallTimeMs = START_MS + seq * 1_000, elapsedRealtimeNs = START_NS + seq * 1_000_000_000,
    lat = lat, lon = lon, altitude = 28.0, speedMps = 1.25f, bearing = 90f, gpsAccuracy = 4.7f, locationAgeMs = 300,
    locationStatus = if (lat == null) "WAITING" else "OK", networkType = "NR_NSA", overrideNetworkType = 3,
    dataNetworkType = 13, nsaLteServing = true, nsaNrCellInfo = true, nsaNrSignal = true, serviceState = 0, dataState = 2,
    isRoaming = false, operatorNumeric = "51089", cellInfoOrigin = "FRESH", cellBandwidthsJson = "[20000,60000]",
    batteryLevel = 79, batteryCurrentUa = -350_000, batteryVoltageMv = 4_100, batteryTempC = 31.5f, isCharging = false,
    rxBytesTotal = 1_000, txBytesTotal = 500, rxBytesApp = 0, txBytesApp = 0, screenOn = false, thermalStatus = 0,
    parseErrors = 0, warningCount = 7,
)

fun cell(
    rat: String, source: String = "CELL_INFO", servingInferred: Boolean = true, reportedServing: Boolean? = true,
    servingSource: String? = null, registered: Boolean? = null, status: String? = null, pci: Int? = null,
    rsrp: Int? = null, rsrq: Int? = null, sinr: Int? = null, rssi: Int? = null, cqi: Int? = null, ta: Int? = null,
    csiRsrp: Int? = null, age: Long? = 13, band: Int? = null, bandSource: String? = null, arfcn: Int? = null,
) = CellEntity(
    measurementId = 0, rat = rat, servingInferred = servingInferred, reportedServing = reportedServing, servingSource = servingSource,
    isRegistered = registered, connectionStatus = status, ciOrNci = null, tac = null, pci = pci, arfcn = arfcn, band = band,
    bandSource = bandSource, bandwidthKhz = null, mcc = null, mnc = null, rsrpDbm = rsrp, rsrqDb = rsrq, sinrDb = sinr,
    rssiDbm = rssi, cqi = cqi, timingAdvance = ta, csiRsrpDbm = csiRsrp, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null,
    ecNoDb = null, source = source, cellInfoAgeMs = age,
)

/** A56 NSA tick: LTE anchor (no RSSNR in CellInfo), NR 440 serving, neighbor 438 demoted, both SignalStrength rows. */
fun a56Cells() = listOf(
    cell("LTE", registered = true, status = "PRIMARY_SERVING", pci = 479, rsrp = -92, rsrq = -7, rssi = -63, cqi = 9, ta = 2, band = 3, bandSource = "API", arfcn = 1625),
    cell("LTE", servingInferred = false, status = "NONE", pci = 478, rsrp = -101),
    cell("NR", servingSource = "SIGNAL_STRENGTH_MATCH", registered = false, status = "SECONDARY_SERVING", pci = 440, rsrp = -101, rsrq = -11, sinr = 7, band = 41, bandSource = "MAPPED", arfcn = 531_870),
    cell("NR", servingInferred = false, servingSource = "SIGNAL_STRENGTH_MATCH", registered = false, status = "NONE", pci = 438, rsrp = -109, rsrq = -18, sinr = -7, band = 41, bandSource = "MAPPED", arfcn = 531_870),
    cell("LTE", source = "SIGNAL_STRENGTH", reportedServing = null, rsrp = -93, rsrq = -9, sinr = 15, rssi = -93, cqi = 13, age = 960),
    cell("NR", source = "SIGNAL_STRENGTH", reportedServing = null, rsrp = -101, rsrq = -11, sinr = 9, csiRsrp = -104, age = 960),
)

class FakeExportDao(
    var session: SessionEntity? = session(),
    val measurements: List<MeasurementEntity> = (0L until 3).map { measurement(it) },
    val cellsPerTick: (Long) -> List<CellEntity> = { a56Cells() },
    val events: List<EventEntity> = emptyList(),
    val warnings: List<WarningWithSeq> = emptyList(),
) : ExportDao {
    var measurementQueries = 0

    override suspend fun session(sessionId: Long) = session

    override suspend fun measurements(sessionId: Long, afterSeq: Long, limit: Int): List<MeasurementEntity> {
        measurementQueries++
        return measurements.filter { it.seq > afterSeq }.take(limit)
    }

    override suspend fun cells(sessionId: Long, fromSeq: Long, toSeq: Long) =
        measurements.filter { it.seq in fromSeq..toSeq }.flatMap { m -> cellsPerTick(m.seq).map { CellWithTick(m.seq, m.wallTimeMs, m.elapsedRealtimeNs, it) } }

    override suspend fun events(sessionId: Long) = events

    override suspend fun warnings(sessionId: Long) = warnings

    override suspend fun measurementCount(sessionId: Long) = measurements.size.toLong()

    override fun observeLastSeq(sessionId: Long) = kotlinx.coroutines.flow.flowOf(measurements.lastOrNull()?.seq)
}

fun warning(code: String, raw: String?) = WarningWithSeq(
    firstSeq = 0,
    warning = ParseWarningEntity(
        id = 1, sessionId = 1, measurementId = 1, elapsedRealtimeNs = START_NS, code = code, rat = "NR", pci = 438,
        field = "connectionStatus", rawValue = raw, lastRawValue = raw, occurrences = 19, firstSeenMs = START_MS, lastSeenMs = START_MS + 2_000,
    ),
)

fun marker(seq: Long, lat: Double? = -0.9, lon: Double? = 100.4) = EventEntity(
    id = seq, sessionId = 1, wallTimeMs = START_MS + seq * 1_000, elapsedRealtimeNs = START_NS + seq * 1_000_000_000,
    type = "USER_MARKER", lat = lat, lon = lon,
)
