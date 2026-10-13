/*
 * Detection rules adapted from NetMonster Core (https://github.com/mroczis/netmonster-core):
 *   feature/postprocess/SamsungEndiannessPostprocessor.kt
 *   feature/postprocess/SamsungInvalidValuesPostprocessor.kt
 *   telephony/mapper/cell/CellMapperLte.kt (RSSNR 0 = not available)
 * Copyright 2019 Michal Mroček. Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Changes from the original: reimplemented on NetLogger's CellSample; every correction emits a ParseWarning
 * that keeps the raw value instead of changing data silently; the byte-swap latch lives for one session
 * instead of being persisted per Android build. Cell ID 0, TAC 0 and LTE CQI 0 on neighbors were added
 * from our own A56 field data (not in NetMonster), as was the NR CSI floor placeholder.
 */
package id.my.pamslab.netlogger.core.telephony.parser

import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.ParseWarning
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.radio.WarningCode
import id.my.pamslab.netlogger.core.model.raw.RawLegacyCellLocation

/** Corrections applied only when Build.MANUFACTURER is Samsung. */
internal object SamsungQuirks {

    private const val ANDROID_12 = 31
    private const val PLACEHOLDER_RSSI = -51
    private const val CSI_RSRQ_FLOOR = -20
    private const val CSI_SINR_FLOOR = -23
    private const val CSI_GAP_DB = 20

    data class SwapResult(val cells: List<CellSample>, val latched: Boolean)

    /**
     * Samsung on Android 12+ reports the serving cell's TAC/LAC with its two bytes swapped in CellInfo
     * (e.g. 5220 instead of 25620). The deprecated getCellLocation() still has the right value, so a swap
     * is detected when the cell IDs match and the area codes are byte-swapped copies of each other. Once
     * seen, the correction stays on for the session, since getCellLocation() is not always available.
     */
    fun fixTacByteSwap(
        cells: List<CellSample>,
        legacy: RawLegacyCellLocation?,
        latched: Boolean,
        sdkInt: Int,
        warnings: MutableList<ParseWarning>,
    ): SwapResult {
        if (sdkInt < ANDROID_12) return SwapResult(cells, latched)
        var nowLatched = latched
        val fixed = cells.map { cell ->
            val tac = cell.tac
            val eligible = cell.connectionStatus == ConnectionStatus.PRIMARY_SERVING && cell.rat != Rat.NR && tac != null
            if (!eligible) return@map cell
            if (!nowLatched && legacy != null && legacy.cid?.toLong() == cell.cellId && isByteSwapped(tac, legacy.lac)) {
                nowLatched = true
            }
            if (nowLatched) {
                warnings += ParseWarning(WarningCode.SAMSUNG_TAC_BYTE_SWAP, cell.rat, cell.pci, "tac", tac.toString())
                cell.copy(tac = swapBytes(tac))
            } else {
                cell
            }
        }
        return SwapResult(fixed, nowLatched)
    }

    /**
     * Neighbor (not serving) cells only: Samsung fills unknown values with placeholders. Seen on the A56
     * (Android 16): neighbors with cell ID 0, TAC 0, CQI 0, TA 0 and RSSNR 0.
     */
    fun dropPlaceholders(cells: List<CellSample>, warnings: MutableList<ParseWarning>): List<CellSample> =
        cells.map { cell ->
            var c = fixNrCsiFloor(fixLteSnrZero(cell, warnings), warnings)
            if (c.isServing) return@map c
            fun drop(field: String, raw: Int): Nothing? {
                warnings += ParseWarning(WarningCode.SAMSUNG_NEIGHBOR_PLACEHOLDER, c.rat, c.pci, field, raw.toString())
                return null
            }
            c.cellId?.takeIf { it == 0L }?.let { c = c.copy(cellId = drop("cellId", it.toInt())) }
            c.tac?.takeIf { it == 0 }?.let { c = c.copy(tac = drop("tac", it)) }
            if (c.rat == Rat.LTE) c.cqi?.takeIf { it == 0 }?.let { c = c.copy(cqi = drop("cqi", it)) }
            when (c.rat) {
                Rat.LTE, Rat.GSM -> {
                    c.rssiDbm?.takeIf { it == PLACEHOLDER_RSSI }?.let { c = c.copy(rssiDbm = drop("rssi", it)) }
                    c.timingAdvance?.takeIf { it <= 0 }?.let { c = c.copy(timingAdvance = drop("ta", it)) }
                }
                Rat.WCDMA -> c.ecNoDb?.takeIf { it == 0 }?.let { c = c.copy(ecNoDb = drop("ecNo", it)) }
                Rat.NR -> Unit
            }
            c
        }

    fun fixLteSnrZero(cell: CellSample, warnings: MutableList<ParseWarning>): CellSample {
        if (cell.rat != Rat.LTE || cell.sinrDb != 0) return cell
        warnings += ParseWarning(WarningCode.SAMSUNG_LTE_SNR_ZERO, cell.rat, cell.pci, "rssnr", "0")
        return cell.copy(sinrDb = null)
    }

    /**
     * A56 (Android 16) reports NR CSI-RSRP / CSI-RSRQ / CSI-SINR as -140 / -20 / -23 while SS-RSRP is about -100:
     * the RSRQ and SINR sit at the floor of Android's ranges and the RSRP is far below SS-RSRP, so nothing was
     * measured. All three become null, each with a warning.
     */
    fun fixNrCsiFloor(cell: CellSample, warnings: MutableList<ParseWarning>): CellSample {
        if (cell.rat != Rat.NR || cell.csiRsrqDb != CSI_RSRQ_FLOOR || cell.csiSinrDb != CSI_SINR_FLOOR) return cell
        val csiRsrp = cell.csiRsrpDbm
        val ssRsrp = cell.rsrpDbm
        if (csiRsrp != null && ssRsrp != null && csiRsrp > ssRsrp - CSI_GAP_DB) return cell
        listOf("csiRsrp" to csiRsrp, "csiRsrq" to cell.csiRsrqDb, "csiSinr" to cell.csiSinrDb).forEach { (field, raw) ->
            if (raw != null) warnings += ParseWarning(WarningCode.SAMSUNG_NR_CSI_PLACEHOLDER, cell.rat, cell.pci, field, raw.toString())
        }
        return cell.copy(csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null)
    }

    fun swapBytes(value: Int): Int = ((value and 0xFF) shl 8) or ((value shr 8) and 0xFF)

    /** True when [a] and [b] are distinct 16-bit values with their two bytes swapped. */
    fun isByteSwapped(a: Int?, b: Int?): Boolean =
        a != null && b != null && a != b && a in 0..0xFFFF && b in 0..0xFFFF && swapBytes(a) == b
}
