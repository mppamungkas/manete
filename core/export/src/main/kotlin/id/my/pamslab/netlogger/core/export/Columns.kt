package id.my.pamslab.netlogger.core.export

import id.my.pamslab.netlogger.core.data.db.CellWithTick
import id.my.pamslab.netlogger.core.data.db.EventEntity
import id.my.pamslab.netlogger.core.data.db.FloorPlanEntity
import id.my.pamslab.netlogger.core.data.db.MeasurementEntity
import id.my.pamslab.netlogger.core.data.db.PlanWaypointEntity
import id.my.pamslab.netlogger.core.data.db.ThroughputTestEntity
import id.my.pamslab.netlogger.core.data.db.WarningWithSeq
import id.my.pamslab.netlogger.core.model.indoor.PlanPosition

/** One CSV column: header name and value. Definitions and units: docs/columns.md (kept in sync by a unit test). */
class Column<T>(val name: String, val value: (T) -> Any?)

private fun <T> col(name: String, value: (T) -> Any?) = Column(name, value)

/** One tap for waypoints.csv, with its plan (decision 006). */
class WaypointRow(val w: PlanWaypointEntity, val plan: FloorPlanEntity?)

/** One tick placed on a floor plan for indoor.csv; [latLon] when the plan is placed on the map. */
class IndoorRow(val m: MeasurementEntity, val position: PlanPosition, val plan: FloorPlanEntity?, val latLon: Pair<Double, Double>? = null)

/** Column sets per file. [time] formats epoch ms as ISO-8601 in the export's time zone. */
class Columns(time: IsoTime) {

    val measurements: List<Column<MeasurementEntity>> = listOf(
        col("seq") { it.seq },
        col("time") { time.format(it.wallTimeMs) },
        col("wallTimeMs") { it.wallTimeMs },
        col("elapsedRealtimeNs") { it.elapsedRealtimeNs },
        col("lat") { it.lat },
        col("lon") { it.lon },
        col("altitude") { it.altitude },
        col("speedMps") { it.speedMps },
        col("bearing") { it.bearing },
        col("gpsAccuracy") { it.gpsAccuracy },
        col("locationAgeMs") { it.locationAgeMs },
        col("locationStatus") { it.locationStatus },
        col("networkType") { it.networkType },
        col("overrideNetworkType") { it.overrideNetworkType },
        col("dataNetworkType") { it.dataNetworkType },
        col("nsaLteServing") { it.nsaLteServing },
        col("nsaNrCellInfo") { it.nsaNrCellInfo },
        col("nsaNrSignal") { it.nsaNrSignal },
        col("serviceState") { it.serviceState },
        col("dataState") { it.dataState },
        col("isRoaming") { it.isRoaming },
        col("operatorNumeric") { it.operatorNumeric },
        col("cellInfoOrigin") { it.cellInfoOrigin },
        col("cellBandwidthsJson") { it.cellBandwidthsJson },
        col("batteryLevel") { it.batteryLevel },
        col("batteryCurrentUa") { it.batteryCurrentUa },
        col("batteryVoltageMv") { it.batteryVoltageMv },
        col("batteryTempC") { it.batteryTempC },
        col("isCharging") { it.isCharging },
        col("rxBytesTotal") { it.rxBytesTotal },
        col("txBytesTotal") { it.txBytesTotal },
        col("rxBytesApp") { it.rxBytesApp },
        col("txBytesApp") { it.txBytesApp },
        col("screenOn") { it.screenOn },
        col("thermalStatus") { it.thermalStatus },
        col("parseErrors") { it.parseErrors },
        col("warningCount") { it.warningCount },
    )

    val cells: List<Column<CellWithTick>> = listOf(
        col("seq") { it.seq },
        col("time") { time.format(it.wallTimeMs) },
        col("wallTimeMs") { it.wallTimeMs },
        col("elapsedRealtimeNs") { it.elapsedRealtimeNs },
        col("rat") { it.cell.rat },
        col("source") { it.cell.source },
        col("reportedServing") { it.cell.reportedServing },
        col("servingInferred") { it.cell.servingInferred },
        col("servingSource") { it.cell.servingSource },
        col("isRegistered") { it.cell.isRegistered },
        col("connectionStatus") { it.cell.connectionStatus },
        col("ciOrNci") { it.cell.ciOrNci },
        col("tac") { it.cell.tac },
        col("pci") { it.cell.pci },
        col("arfcn") { it.cell.arfcn },
        col("band") { it.cell.band },
        col("bandSource") { it.cell.bandSource },
        col("bandwidthKhz") { it.cell.bandwidthKhz },
        col("mcc") { it.cell.mcc },
        col("mnc") { it.cell.mnc },
        col("rsrp") { it.cell.rsrpDbm },
        col("rsrq") { it.cell.rsrqDb },
        col("sinr") { it.cell.sinrDb },
        col("rssi") { it.cell.rssiDbm },
        col("cqi") { it.cell.cqi },
        col("ta") { it.cell.timingAdvance },
        col("csiRsrp") { it.cell.csiRsrpDbm },
        col("csiRsrq") { it.cell.csiRsrqDb },
        col("csiSinr") { it.cell.csiSinrDb },
        col("rscp") { it.cell.rscpDbm },
        col("ecNo") { it.cell.ecNoDb },
        col("cellInfoAgeMs") { it.cell.cellInfoAgeMs },
    )

    val events: List<Column<EventEntity>> = listOf(
        col("time") { time.format(it.wallTimeMs) },
        col("wallTimeMs") { it.wallTimeMs },
        col("elapsedRealtimeNs") { it.elapsedRealtimeNs },
        col("type") { it.type },
        col("fromValue") { it.fromValue },
        col("toValue") { it.toValue },
        col("lat") { it.lat },
        col("lon") { it.lon },
    )

    val throughput: List<Column<ThroughputTestEntity>> = listOf(
        col("startedAt") { time.format(it.startedAtMs) },
        col("startedAtMs") { it.startedAtMs },
        col("startedElapsedNs") { it.startedElapsedNs },
        col("type") { it.type },
        col("serverUrl") { it.serverUrl },
        col("serverLocation") { it.serverLocation },
        col("triggeredBy") { it.triggeredBy },
        col("durationMs") { it.durationMs },
        col("bytes") { it.bytes },
        col("mbps") { it.mbps },
        col("streams") { it.streams },
        col("rttMinMs") { it.rttMinMs },
        col("rttAvgMs") { it.rttAvgMs },
        col("rttMaxMs") { it.rttMaxMs },
        col("jitterMs") { it.jitterMs },
        col("lossPct") { it.lossPct },
        col("captureActive") { it.captureActive },
        col("error") { it.error },
    )

    val warnings: List<Column<WarningWithSeq>> = listOf(
        col("code") { it.warning.code },
        col("rat") { it.warning.rat },
        col("pci") { it.warning.pci },
        col("field") { it.warning.field },
        col("rawValue") { it.warning.rawValue },
        col("lastRawValue") { it.warning.lastRawValue },
        col("occurrences") { it.warning.occurrences },
        col("firstSeen") { time.format(it.warning.firstSeenMs) },
        col("lastSeen") { time.format(it.warning.lastSeenMs) },
        col("firstSeenMs") { it.warning.firstSeenMs },
        col("lastSeenMs") { it.warning.lastSeenMs },
        col("firstSeq") { it.firstSeq },
        col("firstElapsedRealtimeNs") { it.warning.elapsedRealtimeNs },
    )

    val flat: List<Column<FlatRow>> = FlatRow.columns(time)

    val waypoints: List<Column<WaypointRow>> = listOf(
        col("time") { time.format(it.w.wallTimeMs) },
        col("wallTimeMs") { it.w.wallTimeMs },
        col("elapsedRealtimeNs") { it.w.elapsedRealtimeNs },
        col("planUuid") { it.plan?.uuid },
        col("planName") { it.plan?.name },
        col("floor") { it.plan?.floor },
        col("x") { it.w.x.round(1) },
        col("y") { it.w.y.round(1) },
        col("xM") { it.plan?.metresPerPx?.let { m -> (it.w.x * m).round(2) } },
        col("yM") { it.plan?.metresPerPx?.let { m -> (it.w.y * m).round(2) } },
        col("kind") { it.w.kind },
        col("removed") { it.w.removed },
    )

    val indoor: List<Column<IndoorRow>> = listOf(
        col("seq") { it.m.seq },
        col("time") { time.format(it.m.wallTimeMs) },
        col("wallTimeMs") { it.m.wallTimeMs },
        col("elapsedRealtimeNs") { it.m.elapsedRealtimeNs },
        col("planUuid") { it.plan?.uuid },
        col("floor") { it.plan?.floor },
        col("planX") { it.position.x.round(1) },
        col("planY") { it.position.y.round(1) },
        col("planXM") { it.plan?.metresPerPx?.let { m -> (it.position.x * m).round(2) } },
        col("planYM") { it.plan?.metresPerPx?.let { m -> (it.position.y * m).round(2) } },
        col("waypointGapS") { it.position.waypointGapS.round(1) },
        col("lat") { it.latLon?.first?.round(7) },
        col("lon") { it.latLon?.second?.round(7) },
    )
}

fun <T> CsvWriter.writeAll(columns: List<Column<T>>, rows: Iterable<T>) {
    rows.forEach { r -> row(columns.map { it.value(r) }) }
}

/** Positions are taps by finger: sub-pixel or sub-centimetre digits would only suggest a precision that is not there. */
private fun Double.round(decimals: Int): Double {
    val f = Math.pow(10.0, decimals.toDouble())
    return Math.round(this * f) / f
}
