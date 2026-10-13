package id.my.pamslab.netlogger.core.data

import id.my.pamslab.netlogger.core.data.db.CellEntity
import id.my.pamslab.netlogger.core.data.db.MeasurementEntity
import id.my.pamslab.netlogger.core.data.db.ParseWarningEntity
import id.my.pamslab.netlogger.core.data.db.WarningBump
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.ParseWarning

/** Pure mapping from domain ticks to rows. */
internal object TickMapper {

    fun measurement(sessionId: Long, tick: Tick): MeasurementEntity {
        val r = tick.radio
        val fix = tick.location
        val d = tick.device
        return MeasurementEntity(
            sessionId = sessionId,
            seq = tick.seq,
            wallTimeMs = r.wallTimeMs,
            elapsedRealtimeNs = r.elapsedRealtimeNs,
            lat = fix?.latitude,
            lon = fix?.longitude,
            altitude = fix?.altitudeM,
            speedMps = fix?.speedMps,
            bearing = fix?.bearingDeg,
            gpsAccuracy = fix?.accuracyM,
            locationAgeMs = tick.locationAgeMs,
            locationStatus = tick.locationStatus.name,
            networkType = r.networkType.name,
            overrideNetworkType = r.nsaInputs.overrideNetworkType,
            dataNetworkType = r.nsaInputs.dataNetworkType,
            nsaLteServing = r.nsaInputs.lteServing,
            nsaNrCellInfo = r.nsaInputs.nrServingCellInfo,
            nsaNrSignal = r.nsaInputs.nrSignalStrength,
            serviceState = r.serviceState,
            dataState = r.dataState,
            isRoaming = r.isRoaming,
            operatorNumeric = r.operatorNumeric,
            cellInfoOrigin = r.cellInfoOrigin.name,
            cellBandwidthsJson = r.cellBandwidthsKhz.joinToString(",", "[", "]"),
            batteryLevel = d.battery.levelPct,
            batteryCurrentUa = d.battery.currentUa,
            batteryVoltageMv = d.battery.voltageMv,
            batteryTempC = d.battery.tempC,
            isCharging = d.battery.isCharging,
            rxBytesTotal = d.traffic.rxBytesTotal,
            txBytesTotal = d.traffic.txBytesTotal,
            rxBytesApp = d.traffic.rxBytesApp,
            txBytesApp = d.traffic.txBytesApp,
            screenOn = d.screenOn,
            thermalStatus = d.thermalStatus,
            parseErrors = r.parseErrors,
        )
    }

    /** measurementId is filled in by the DAO transaction. */
    fun cell(c: CellSample) = CellEntity(
        measurementId = 0,
        rat = c.rat.name,
        isServing = c.isServing,
        isRegistered = c.isRegistered,
        connectionStatus = c.connectionStatus?.name,
        ciOrNci = c.cellId,
        tac = c.tac,
        pci = c.pci,
        arfcn = c.arfcn,
        band = c.band,
        bandSource = c.bandSource?.name,
        bandwidthKhz = c.bandwidthKhz,
        mcc = c.mcc,
        mnc = c.mnc,
        rsrpDbm = c.rsrpDbm,
        rsrqDb = c.rsrqDb,
        sinrDb = c.sinrDb,
        rssiDbm = c.rssiDbm,
        cqi = c.cqi,
        timingAdvance = c.timingAdvance,
        csiRsrpDbm = c.csiRsrpDbm,
        csiRsrqDb = c.csiRsrqDb,
        csiSinrDb = c.csiSinrDb,
        rscpDbm = c.rscpDbm,
        ecNoDb = c.ecNoDb,
        source = c.source.name,
        cellInfoAgeMs = c.cellInfoAgeMs,
    )
}

/**
 * Per-session warning deduplication: each (code, rat, pci, field) gets one row; later occurrences bump its counter.
 * Not thread-safe; one instance per recording session, used from the single tick loop.
 */
internal class WarningDeduplicator(private val sessionId: Long) {

    data class Key(val code: String, val rat: String?, val pci: Int?, val field: String?)

    data class Plan(val keys: List<Key>, val newRows: List<ParseWarningEntity>, val bumps: List<WarningBump>)

    private val rowIds = HashMap<Key, Long>()

    fun plan(warnings: List<ParseWarning>, wallTimeMs: Long, elapsedRealtimeNs: Long): Plan {
        val grouped = warnings.groupBy { Key(it.code.name, it.rat?.name, it.pci, it.field) }
        val keys = mutableListOf<Key>()
        val newRows = mutableListOf<ParseWarningEntity>()
        val bumps = mutableListOf<WarningBump>()
        grouped.forEach { (key, list) ->
            val existing = rowIds[key]
            if (existing != null) {
                bumps += WarningBump(existing, list.size.toLong(), wallTimeMs, list.last().rawValue)
            } else {
                keys += key
                newRows += ParseWarningEntity(
                    sessionId = sessionId,
                    measurementId = 0,
                    elapsedRealtimeNs = elapsedRealtimeNs,
                    code = key.code,
                    rat = key.rat,
                    pci = key.pci,
                    field = key.field,
                    rawValue = list.first().rawValue,
                    lastRawValue = list.last().rawValue,
                    occurrences = list.size.toLong(),
                    firstSeenMs = wallTimeMs,
                    lastSeenMs = wallTimeMs,
                )
            }
        }
        return Plan(keys, newRows, bumps)
    }

    /** Call after the transaction committed, with the ids returned for [Plan.newRows]. */
    fun committed(plan: Plan, newIds: List<Long>) {
        plan.keys.zip(newIds).forEach { (key, id) -> rowIds[key] = id }
    }
}
