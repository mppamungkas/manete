package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.data.db.EventEntity
import id.my.pamslab.netlogger.core.export.FlatRow

/**
 * One tick with a GPS fix, reduced to what the map shows. Signal values follow flat.csv: CELL_INFO first, else
 * SIGNAL_STRENGTH, with the source kept for the tap card.
 */
data class TrackPoint(
    val seq: Long,
    val wallTimeMs: Long,
    val lat: Double,
    val lon: Double,
    val gpsAccuracyM: Float?,
    val locationAgeMs: Long?,
    val speedMps: Float?,
    val networkType: String,
    val overrideNetworkType: Int?,
    val ltePci: Int?,
    val lteEarfcn: Int?,
    val lteBand: Int?,
    val lteRsrp: Int?,
    val lteRsrpSource: String?,
    val lteSinr: Int?,
    val lteSinrSource: String?,
    val lteAgeMs: Long?,
    val nrPci: Int?,
    val nrArfcn: Int?,
    val nrBand: Int?,
    val nrServingSource: String?,
    val nrRsrp: Int?,
    val nrRsrpSource: String?,
    val nrSinr: Int?,
    val nrSinrSource: String?,
    val nrAgeMs: Long?,
) {
    companion object {
        /** Null for ticks without a fix: they cannot be placed, and are never drawn at 0/0. */
        fun of(row: FlatRow): TrackPoint? {
            val m = row.m
            val lat = m.lat?.takeIf { it.isFinite() } ?: return null
            val lon = m.lon?.takeIf { it.isFinite() } ?: return null
            val lteRsrp = row.pick(row.lte, row.lteSignal) { it.rsrpDbm }
            val lteSinr = row.pick(row.lte, row.lteSignal) { it.sinrDb }
            val nrRsrp = row.pick(row.nr, row.nrSignal) { it.rsrpDbm }
            val nrSinr = row.pick(row.nr, row.nrSignal) { it.sinrDb }
            return TrackPoint(
                seq = m.seq, wallTimeMs = m.wallTimeMs, lat = lat, lon = lon, gpsAccuracyM = m.gpsAccuracy,
                locationAgeMs = m.locationAgeMs, speedMps = m.speedMps, networkType = m.networkType,
                overrideNetworkType = m.overrideNetworkType,
                ltePci = row.lte?.pci, lteEarfcn = row.lte?.arfcn, lteBand = row.lte?.band,
                lteRsrp = lteRsrp.value, lteRsrpSource = lteRsrp.source, lteSinr = lteSinr.value, lteSinrSource = lteSinr.source,
                lteAgeMs = row.lte?.cellInfoAgeMs,
                nrPci = row.nr?.pci, nrArfcn = row.nr?.arfcn, nrBand = row.nr?.band, nrServingSource = row.nr?.servingSource,
                nrRsrp = nrRsrp.value, nrRsrpSource = nrRsrp.source, nrSinr = nrSinr.value, nrSinrSource = nrSinr.source,
                nrAgeMs = row.nr?.cellInfoAgeMs ?: row.nrSignal?.cellInfoAgeMs,
            )
        }
    }
}

/** Everything drawn for one session. [ticks] counts all ticks, [points] only those with a fix. */
data class Track(val sessionId: Long, val points: List<TrackPoint>, val events: List<EventEntity>, val ticks: Long)
