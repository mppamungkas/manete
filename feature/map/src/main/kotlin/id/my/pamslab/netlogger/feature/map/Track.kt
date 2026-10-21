package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.data.db.EventEntity
import id.my.pamslab.netlogger.core.export.FlatRow

/**
 * One tick's radio values, reduced to what the map and the floor plan view show. Signal values follow flat.csv:
 * CELL_INFO first, else SIGNAL_STRENGTH, with the source kept for the tap card. Needs no GPS fix.
 */
data class TickValues(
    val seq: Long,
    val wallTimeMs: Long,
    val elapsedRealtimeNs: Long,
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
        fun of(row: FlatRow): TickValues {
            val m = row.m
            val lteRsrp = row.pick(row.lte, row.lteSignal) { it.rsrpDbm }
            val lteSinr = row.pick(row.lte, row.lteSignal) { it.sinrDb }
            val nrRsrp = row.pick(row.nr, row.nrSignal) { it.rsrpDbm }
            val nrSinr = row.pick(row.nr, row.nrSignal) { it.sinrDb }
            return TickValues(
                seq = m.seq, wallTimeMs = m.wallTimeMs, elapsedRealtimeNs = m.elapsedRealtimeNs, networkType = m.networkType,
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

/** One tick with a GPS fix: its [values] and where it is on the map. */
data class TrackPoint(
    val values: TickValues,
    val lat: Double,
    val lon: Double,
    val gpsAccuracyM: Float?,
    val locationAgeMs: Long?,
    val speedMps: Float?,
) {
    val seq: Long get() = values.seq

    companion object {
        /** Null for ticks without a fix: they cannot be placed, and are never drawn at 0/0. */
        fun of(row: FlatRow): TrackPoint? {
            val m = row.m
            val lat = m.lat?.takeIf { it.isFinite() } ?: return null
            val lon = m.lon?.takeIf { it.isFinite() } ?: return null
            return TrackPoint(TickValues.of(row), lat, lon, m.gpsAccuracy, m.locationAgeMs, m.speedMps)
        }
    }
}

/**
 * Everything drawn for one session. [ticks] counts all ticks, [points] only those with a fix; [values] has every tick
 * (in order), for the floor plan view, where ticks need no fix.
 */
data class Track(
    val sessionId: Long,
    val points: List<TrackPoint>,
    val events: List<EventEntity>,
    val ticks: Long,
    val values: List<TickValues> = points.map { it.values },
)
