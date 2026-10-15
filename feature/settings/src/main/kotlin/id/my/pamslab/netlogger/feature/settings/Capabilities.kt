package id.my.pamslab.netlogger.feature.settings

import id.my.pamslab.netlogger.core.data.FieldCoverage
import id.my.pamslab.netlogger.core.data.db.RatCoverageRow
import id.my.pamslab.netlogger.core.model.radio.BandSource
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin

/** What one field looks like on this phone right now (decision 003, Phone capabilities). */
internal enum class CapStatus { AVAILABLE, LIMITED, MISSING, NEEDS_LTE, NEEDS_NR }

internal enum class CapGroup { LTE, NR, OTHER }

/** [isRecorded]: stored per row, so recorded sessions can tell how often the phone gave it. */
internal enum class CapField(val group: CapGroup, val isRecorded: Boolean = true) {
    LTE_RSRP(CapGroup.LTE), LTE_RSRQ(CapGroup.LTE), LTE_SINR(CapGroup.LTE), LTE_RSSI(CapGroup.LTE), LTE_CQI(CapGroup.LTE),
    LTE_TA(CapGroup.LTE), LTE_PCI(CapGroup.LTE), LTE_EARFCN(CapGroup.LTE), LTE_BAND(CapGroup.LTE), LTE_CELL_ID(CapGroup.LTE),
    NR_CELL_INFO(CapGroup.NR), NR_SIGNAL_STRENGTH(CapGroup.NR), NR_SS_RSRP(CapGroup.NR), NR_SS_RSRQ(CapGroup.NR),
    NR_SS_SINR(CapGroup.NR), NR_CSI_RSRP(CapGroup.NR), NR_PCI(CapGroup.NR), NR_ARFCN(CapGroup.NR), NR_BAND(CapGroup.NR),
    CELL_BANDWIDTHS(CapGroup.OTHER), NEIGHBOURS(CapGroup.OTHER), FRESH_CELL_INFO(CapGroup.OTHER, isRecorded = false),
}

/** [value]: the latest value seen, with its unit (units are not translated); null when there is none to show. */
internal data class CapRow(val field: CapField, val status: CapStatus, val value: String?)

/** Ticks with a value out of ticks where the field could have one. */
internal data class Share(val count: Long, val of: Long)

/** NSA display overrides: NR_NSA, NR_NSA_MMWAVE, NR_ADVANCED (TelephonyDisplayInfo). */
private val NR_OVERRIDES = setOf(3, 4, 5)

/**
 * Evaluates a few consecutive radio snapshots (pure). A field counts as available when any snapshot had it; the
 * value shown is the latest. Without LTE or NR coverage the fields of that RAT say so instead of "missing".
 */
internal fun evaluateCapabilities(snapshots: List<RadioSnapshot>): List<CapRow> {
    val lteNow = snapshots.any { lteRows(it).isNotEmpty() }
    val nrNow = snapshots.any { nrRows(it).isNotEmpty() || it.nsaInputs.overrideNetworkType in NR_OVERRIDES }

    fun lte(field: CapField, unit: String, get: (CellSample) -> Int?): CapRow {
        if (!lteNow) return CapRow(field, CapStatus.NEEDS_LTE, null)
        val v = latest(snapshots, ::lteRows, get)
        return CapRow(field, if (v != null) CapStatus.AVAILABLE else CapStatus.MISSING, v?.let { "$it$unit" })
    }

    fun nr(field: CapField, unit: String, get: (CellSample) -> Int?): CapRow {
        if (!nrNow) return CapRow(field, CapStatus.NEEDS_NR, null)
        val v = latest(snapshots, ::nrRows, get)
        return CapRow(field, if (v != null) CapStatus.AVAILABLE else CapStatus.MISSING, v?.let { "$it$unit" })
    }

    fun band(field: CapField, rows: (RadioSnapshot) -> List<CellSample>, prefix: String, needs: CapStatus, now: Boolean): CapRow {
        if (!now) return CapRow(field, needs, null)
        val cell = snapshots.asReversed().firstNotNullOfOrNull { s -> rows(s).firstOrNull { it.band != null } }
            ?: return CapRow(field, CapStatus.MISSING, null)
        val api = cell.bandSource == BandSource.API
        return CapRow(field, if (api) CapStatus.AVAILABLE else CapStatus.LIMITED, "$prefix${cell.band} (${if (api) "API" else "mapped"})")
    }

    val lteIdentity = if (!lteNow) CapRow(CapField.LTE_CELL_ID, CapStatus.NEEDS_LTE, null) else {
        val ci = latest(snapshots, ::lteRows) { it.cellId }
        val tac = latest(snapshots, ::lteRows) { it.tac }
        when {
            ci != null && tac != null -> CapRow(CapField.LTE_CELL_ID, CapStatus.AVAILABLE, "CI + TAC")
            ci != null || tac != null -> CapRow(CapField.LTE_CELL_ID, CapStatus.LIMITED, if (ci != null) "CI only" else "TAC only")
            else -> CapRow(CapField.LTE_CELL_ID, CapStatus.MISSING, null)
        }
    }

    fun nrFrom(field: CapField, source: CellSource) = when {
        !nrNow -> CapRow(field, CapStatus.NEEDS_NR, null)
        snapshots.any { s -> nrRows(s).any { it.source == source } } -> CapRow(field, CapStatus.AVAILABLE, null)
        else -> CapRow(field, CapStatus.MISSING, null)
    }

    val bandwidths = snapshots.lastOrNull { it.cellBandwidthsKhz.isNotEmpty() }?.cellBandwidthsKhz
    val neighbours = snapshots.maxOfOrNull { s -> s.cells.count { !it.servingInferred && it.source == CellSource.CELL_INFO } } ?: 0
    val fresh = snapshots.count { it.cellInfoOrigin == CellInfoOrigin.FRESH }

    return listOf(
        lte(CapField.LTE_RSRP, " dBm") { it.rsrpDbm },
        lte(CapField.LTE_RSRQ, " dB") { it.rsrqDb },
        lte(CapField.LTE_SINR, " dB") { it.sinrDb },
        lte(CapField.LTE_RSSI, " dBm") { it.rssiDbm },
        lte(CapField.LTE_CQI, "") { it.cqi },
        lte(CapField.LTE_TA, "") { it.timingAdvance },
        lte(CapField.LTE_PCI, "") { it.pci },
        lte(CapField.LTE_EARFCN, "") { it.arfcn },
        band(CapField.LTE_BAND, ::lteRows, "B", CapStatus.NEEDS_LTE, lteNow),
        lteIdentity,
        nrFrom(CapField.NR_CELL_INFO, CellSource.CELL_INFO),
        nrFrom(CapField.NR_SIGNAL_STRENGTH, CellSource.SIGNAL_STRENGTH),
        nr(CapField.NR_SS_RSRP, " dBm") { it.rsrpDbm },
        nr(CapField.NR_SS_RSRQ, " dB") { it.rsrqDb },
        nr(CapField.NR_SS_SINR, " dB") { it.sinrDb },
        nr(CapField.NR_CSI_RSRP, " dBm") { it.csiRsrpDbm },
        nr(CapField.NR_PCI, "") { it.pci },
        nr(CapField.NR_ARFCN, "") { it.arfcn },
        band(CapField.NR_BAND, ::nrRows, "n", CapStatus.NEEDS_NR, nrNow),
        when {
            bandwidths == null -> CapRow(CapField.CELL_BANDWIDTHS, CapStatus.MISSING, null)
            else -> CapRow(CapField.CELL_BANDWIDTHS, CapStatus.AVAILABLE, bandwidths.joinToString(" + ") { "${it / 1000}" } + " MHz")
        },
        CapRow(CapField.NEIGHBOURS, if (neighbours > 0) CapStatus.AVAILABLE else CapStatus.MISSING, neighbours.takeIf { it > 0 }?.toString()),
        CapRow(
            CapField.FRESH_CELL_INFO,
            when {
                snapshots.isEmpty() || fresh == 0 -> CapStatus.MISSING
                fresh == snapshots.size -> CapStatus.AVAILABLE
                else -> CapStatus.LIMITED
            },
            if (snapshots.isEmpty()) null else "$fresh of ${snapshots.size}",
        ),
    )
}

/** The field's share in recorded sessions; null when no recorded tick could have it or it is not recorded. */
internal fun recordedShare(field: CapField, c: FieldCoverage): Share? {
    fun lte(get: (RatCoverageRow) -> Long) = Share(get(c.lte), c.lte.ticks)
    fun nr(get: (RatCoverageRow) -> Long) = Share(get(c.nr), c.nr.ticks)
    val share = when (field) {
        CapField.LTE_RSRP -> lte { it.rsrp }
        CapField.LTE_RSRQ -> lte { it.rsrq }
        CapField.LTE_SINR -> lte { it.sinr }
        CapField.LTE_RSSI -> lte { it.rssi }
        CapField.LTE_CQI -> lte { it.cqi }
        CapField.LTE_TA -> lte { it.ta }
        CapField.LTE_PCI -> lte { it.pci }
        CapField.LTE_EARFCN -> lte { it.arfcn }
        CapField.LTE_BAND -> lte { it.band }
        CapField.LTE_CELL_ID -> lte { minOf(it.cellId, it.tac) }
        CapField.NR_CELL_INFO -> Share(c.nrCellInfoTicks, c.nr.ticks)
        CapField.NR_SIGNAL_STRENGTH -> Share(c.nrSignalStrengthTicks, c.nr.ticks)
        CapField.NR_SS_RSRP -> nr { it.rsrp }
        CapField.NR_SS_RSRQ -> nr { it.rsrq }
        CapField.NR_SS_SINR -> nr { it.sinr }
        CapField.NR_CSI_RSRP -> nr { it.csiRsrp }
        CapField.NR_PCI -> nr { it.pci }
        CapField.NR_ARFCN -> nr { it.arfcn }
        CapField.NR_BAND -> nr { it.band }
        CapField.CELL_BANDWIDTHS -> Share(c.bandwidthTicks, c.ticks)
        CapField.NEIGHBOURS -> Share(c.neighborTicks, c.ticks)
        CapField.FRESH_CELL_INFO -> null
    }
    return share?.takeIf { it.of > 0 }
}

/** Serving cells of one RAT, CellInfo rows before SignalStrength rows. */
private fun rows(s: RadioSnapshot, rat: Rat) = s.cells.filter { it.rat == rat && it.servingInferred }.sortedBy { it.source != CellSource.CELL_INFO }

private fun lteRows(s: RadioSnapshot) = rows(s, Rat.LTE)

private fun nrRows(s: RadioSnapshot) = rows(s, Rat.NR)

private fun <T : Any> latest(snapshots: List<RadioSnapshot>, rows: (RadioSnapshot) -> List<CellSample>, get: (CellSample) -> T?): T? =
    snapshots.asReversed().firstNotNullOfOrNull { s -> rows(s).firstNotNullOfOrNull(get) }
