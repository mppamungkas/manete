package id.my.pamslab.netlogger.core.export

import id.my.pamslab.netlogger.core.data.db.CellEntity
import id.my.pamslab.netlogger.core.data.db.MeasurementEntity

/** A field value and where it came from: CELL_INFO, SIGNAL_STRENGTH, or null when neither had it. */
data class Sourced(val value: Int?, val source: String?)

/**
 * One flat.csv row: the tick, its serving LTE cell and its serving NR cell (decision 001 addenda).
 *
 * - Identity (cell ID, TAC, PCI, ARFCN, band) comes only from the CELL_INFO serving cell.
 * - Each signal field takes the CELL_INFO value, else the SIGNAL_STRENGTH value, with a `*Source` column.
 * - LTE: the registered (else PRIMARY_SERVING, else first) `servingInferred` LTE CELL_INFO cell.
 * - NR: the `servingInferred` NR CELL_INFO cell; with several (NR CA) the strongest SS-RSRP. `nrServingCount` says how many.
 */
class FlatRow(
    val m: MeasurementEntity,
    val lte: CellEntity?,
    val lteSignal: CellEntity?,
    val nr: CellEntity?,
    val nrSignal: CellEntity?,
    val nrServingCount: Int,
) {
    fun pick(cell: CellEntity?, signal: CellEntity?, field: (CellEntity) -> Int?): Sourced {
        cell?.let(field)?.let { return Sourced(it, CELL_INFO) }
        signal?.let(field)?.let { return Sourced(it, SIGNAL_STRENGTH) }
        return Sourced(null, null)
    }

    companion object {
        const val CELL_INFO = "CELL_INFO"
        const val SIGNAL_STRENGTH = "SIGNAL_STRENGTH"

        fun of(m: MeasurementEntity, cells: List<CellEntity>): FlatRow {
            fun serving(rat: String) = cells.filter { it.rat == rat && it.source == CELL_INFO && it.servingInferred }
            fun signal(rat: String) = cells.firstOrNull { it.rat == rat && it.source == SIGNAL_STRENGTH }
            val lteServing = serving("LTE")
            val lte = lteServing.firstOrNull { it.isRegistered == true }
                ?: lteServing.firstOrNull { it.connectionStatus == "PRIMARY_SERVING" }
                ?: lteServing.firstOrNull()
            val nrServing = serving("NR")
            // maxByOrNull keeps the first of equal values; null RSRP ranks lowest.
            val nr = nrServing.maxByOrNull { it.rsrpDbm ?: Int.MIN_VALUE }
            return FlatRow(m, lte, signal("LTE"), nr, signal("NR"), nrServing.size)
        }

        fun columns(time: IsoTime): List<Column<FlatRow>> {
            val cols = mutableListOf<Column<FlatRow>>()
            fun add(name: String, value: (FlatRow) -> Any?) { cols += Column(name, value) }
            fun sourced(name: String, cell: (FlatRow) -> CellEntity?, signal: (FlatRow) -> CellEntity?, field: (CellEntity) -> Int?) {
                add(name) { r -> r.pick(cell(r), signal(r), field).value }
                add(name + "Source") { r -> r.pick(cell(r), signal(r), field).source }
            }

            add("seq") { it.m.seq }
            add("time") { time.format(it.m.wallTimeMs) }
            add("wallTimeMs") { it.m.wallTimeMs }
            add("elapsedRealtimeNs") { it.m.elapsedRealtimeNs }
            add("lat") { it.m.lat }
            add("lon") { it.m.lon }
            add("altitude") { it.m.altitude }
            add("speedMps") { it.m.speedMps }
            add("gpsAccuracy") { it.m.gpsAccuracy }
            add("locationAgeMs") { it.m.locationAgeMs }
            add("networkType") { it.m.networkType }
            add("overrideNetworkType") { it.m.overrideNetworkType }
            add("operatorNumeric") { it.m.operatorNumeric }
            add("cellBandwidthsJson") { it.m.cellBandwidthsJson }

            add("lteCi") { it.lte?.ciOrNci }
            add("lteTac") { it.lte?.tac }
            add("ltePci") { it.lte?.pci }
            add("lteEarfcn") { it.lte?.arfcn }
            add("lteBand") { it.lte?.band }
            add("lteBandSource") { it.lte?.bandSource }
            add("lteBandwidthKhz") { it.lte?.bandwidthKhz }
            add("lteCellInfoAgeMs") { it.lte?.cellInfoAgeMs }
            sourced("lteRsrp", { it.lte }, { it.lteSignal }) { it.rsrpDbm }
            sourced("lteRsrq", { it.lte }, { it.lteSignal }) { it.rsrqDb }
            sourced("lteSinr", { it.lte }, { it.lteSignal }) { it.sinrDb }
            sourced("lteRssi", { it.lte }, { it.lteSignal }) { it.rssiDbm }
            sourced("lteCqi", { it.lte }, { it.lteSignal }) { it.cqi }
            sourced("lteTa", { it.lte }, { it.lteSignal }) { it.timingAdvance }
            add("lteSignalStrengthAgeMs") { it.lteSignal?.cellInfoAgeMs }

            add("nrServingCount") { it.nrServingCount }
            add("nrServingSource") { it.nr?.servingSource }
            add("nrNci") { it.nr?.ciOrNci }
            add("nrTac") { it.nr?.tac }
            add("nrPci") { it.nr?.pci }
            add("nrArfcn") { it.nr?.arfcn }
            add("nrBand") { it.nr?.band }
            add("nrBandSource") { it.nr?.bandSource }
            add("nrCellInfoAgeMs") { it.nr?.cellInfoAgeMs }
            sourced("nrRsrp", { it.nr }, { it.nrSignal }) { it.rsrpDbm }
            sourced("nrRsrq", { it.nr }, { it.nrSignal }) { it.rsrqDb }
            sourced("nrSinr", { it.nr }, { it.nrSignal }) { it.sinrDb }
            sourced("nrCsiRsrp", { it.nr }, { it.nrSignal }) { it.csiRsrpDbm }
            sourced("nrCsiRsrq", { it.nr }, { it.nrSignal }) { it.csiRsrqDb }
            sourced("nrCsiSinr", { it.nr }, { it.nrSignal }) { it.csiSinrDb }
            add("nrSignalStrengthAgeMs") { it.nrSignal?.cellInfoAgeMs }

            add("batteryLevel") { it.m.batteryLevel }
            add("batteryCurrentUa") { it.m.batteryCurrentUa }
            add("isCharging") { it.m.isCharging }
            add("screenOn") { it.m.screenOn }
            add("thermalStatus") { it.m.thermalStatus }
            add("rxBytesTotal") { it.m.rxBytesTotal }
            add("txBytesTotal") { it.m.txBytesTotal }
            add("parseErrors") { it.m.parseErrors }
            add("warningCount") { it.m.warningCount }
            return cols
        }
    }
}
