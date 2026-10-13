package id.ac.pnp.netlogger.core.telephony.parser

import id.ac.pnp.netlogger.core.model.radio.BandSource
import id.ac.pnp.netlogger.core.model.radio.CellSample
import id.ac.pnp.netlogger.core.model.radio.CellSource
import id.ac.pnp.netlogger.core.model.radio.ConnectionStatus
import id.ac.pnp.netlogger.core.model.radio.ParseWarning
import id.ac.pnp.netlogger.core.model.radio.Rat
import id.ac.pnp.netlogger.core.model.radio.WarningCode
import id.ac.pnp.netlogger.core.model.raw.CellInfoOrigin
import id.ac.pnp.netlogger.core.model.raw.DeviceTraits
import id.ac.pnp.netlogger.core.model.raw.RawCell
import id.ac.pnp.netlogger.core.model.raw.RawCellInfoResult
import id.ac.pnp.netlogger.core.model.raw.RawGsmCell
import id.ac.pnp.netlogger.core.model.raw.RawLegacyCellLocation
import id.ac.pnp.netlogger.core.model.raw.RawLteCell
import id.ac.pnp.netlogger.core.model.raw.RawLteSignal
import id.ac.pnp.netlogger.core.model.raw.RawNrCell
import id.ac.pnp.netlogger.core.model.raw.RawNrSignal
import id.ac.pnp.netlogger.core.model.raw.RawSignalStrength
import id.ac.pnp.netlogger.core.model.raw.RawWcdmaCell

data class ParseInput(
    val cellInfo: RawCellInfoResult,
    val signalStrength: RawSignalStrength?,
    val device: DeviceTraits,
    /** elapsedRealtime of the tick, in ms; cell ages are measured against it. */
    val nowElapsedMs: Long,
    /** Only read on Samsung, for the TAC byte-swap check. */
    val legacyCellLocation: RawLegacyCellLocation? = null,
    /** The TAC byte swap was already seen in this session, so apply it even without a fresh comparison. */
    val samsungTacSwapLatched: Boolean = false,
    /** ServiceState.getCellBandwidths(); null when unknown. CA evidence for SECONDARY_SERVING cells. */
    val cellBandwidthsKhz: List<Int>? = null,
    /** TelephonyDisplayInfo.getOverrideNetworkType(); null when unknown. */
    val overrideNetworkType: Int? = null,
    /** MCC of the registered network (ServiceState); picks between overlapping bands in [BandMapper]. */
    val registeredMcc: String? = null,
)

data class ParseResult(
    val cells: List<CellSample>,
    val warnings: List<ParseWarning>,
    /** At least one valid NR value was present in SignalStrength (an NSA input, even if not used as a row). */
    val nrSignalStrengthPresent: Boolean,
    /** Feed back as [ParseInput.samsungTacSwapLatched] on the next tick. */
    val samsungTacSwapLatched: Boolean,
)

/**
 * Pure mapping from raw telephony DTOs to [CellSample]s. Never throws on bad data: sentinels become null
 * silently, implausible values become null with an OUT_OF_RANGE warning, and OEM corrections are applied
 * with a warning that keeps the raw value.
 */
class CellParser(private val bandMapper: BandMapper = BandMapper.None) {

    fun parse(input: ParseInput): ParseResult {
        val warnings = mutableListOf<ParseWarning>()
        val ctx = Ctx(warnings, input.nowElapsedMs, input.registeredMcc)

        if (input.cellInfo.extractionFailures > 0) {
            warnings += ParseWarning(WarningCode.EXTRACTION_FAILED, rawValue = input.cellInfo.extractionFailures.toString())
        }
        input.signalStrength?.extractionFailures?.takeIf { it > 0 }?.let {
            warnings += ParseWarning(WarningCode.EXTRACTION_FAILED, field = "signalStrength", rawValue = it.toString())
        }
        if (input.cellInfo.origin == CellInfoOrigin.UNAVAILABLE) {
            warnings += ParseWarning(WarningCode.CELL_INFO_UNAVAILABLE, rawValue = input.cellInfo.errorCode?.toString())
        }
        if (input.cellInfo.unsupportedCells > 0) {
            warnings += ParseWarning(WarningCode.UNSUPPORTED_CELL_TYPE, rawValue = input.cellInfo.unsupportedCells.toString())
        }

        var cells = input.cellInfo.cells.map { raw ->
            when (raw) {
                is RawLteCell -> ctx.lte(raw)
                is RawNrCell -> ctx.nr(raw)
                is RawWcdmaCell -> ctx.wcdma(raw)
                is RawGsmCell -> ctx.gsm(raw)
            }
        }

        cells = demoteFalseSecondaries(cells, input, warnings)
        cells = inferNsaSecondary(cells, input, warnings)

        var latched = input.samsungTacSwapLatched
        if (input.device.isSamsung) {
            val swap = SamsungQuirks.fixTacByteSwap(cells, input.legacyCellLocation, latched, input.device.sdkInt, warnings)
            cells = swap.cells
            latched = swap.latched
            cells = SamsungQuirks.dropPlaceholders(cells, warnings)
        }

        // Always kept next to the CELL_INFO rows, never merged or deduplicated: the two sources arrive at
        // different times and fill different fields (moto g45: RSSNR only in SignalStrength), and their
        // differences are useful for validation. Consumers pick per field (CELL_INFO first).
        val ssCells = signalStrengthCells(input.signalStrength, ctx, input.device)
        val nrSsPresent = ssCells.any { it.rat == Rat.NR }
        cells = cells + ssCells

        return ParseResult(cells, warnings, nrSsPresent, latched)
    }

    /**
     * Some modems (Samsung A56) flag ordinary neighbors as SECONDARY_SERVING. A same-RAT secondary is only
     * kept as serving when it can be a CA component carrier: not on the primary's ARFCN, and the phone shows
     * CA (the LTE_CA override, or more than one cell bandwidth of the primary's RAT). With no CA information at
     * all, the reported status is kept. An NR secondary under an LTE primary is EN-DC, not CA, and is never
     * touched. For an LTE primary only LTE channel bandwidths count: the A56 lists a 60000 kHz entry (NR or
     * stale) next to its single LTE carrier.
     */
    private fun demoteFalseSecondaries(cells: List<CellSample>, input: ParseInput, warnings: MutableList<ParseWarning>): List<CellSample> {
        val primary = cells.firstOrNull { it.source == CellSource.CELL_INFO && it.connectionStatus == ConnectionStatus.PRIMARY_SERVING }
            ?: return cells
        val bandwidths = input.cellBandwidthsKhz
        val caKnown = bandwidths != null || input.overrideNetworkType != null
        val carriers = if (primary.rat == Rat.LTE) bandwidths.orEmpty().filter { it in LTE_BANDWIDTHS_KHZ } else bandwidths.orEmpty()
        val caShown = carriers.size > 1 || input.overrideNetworkType == OVERRIDE_LTE_CA
        return cells.map { cell ->
            val suspect = cell.connectionStatus == ConnectionStatus.SECONDARY_SERVING &&
                cell.isRegistered != true &&
                cell.rat == primary.rat
            val sameCarrier = cell.arfcn != null && cell.arfcn == primary.arfcn
            if (!suspect || !(sameCarrier || (caKnown && !caShown))) return@map cell
            warnings += ParseWarning(WarningCode.SECONDARY_SERVING_WITHOUT_CA, cell.rat, cell.pci, "connectionStatus", CONNECTION_SECONDARY_SERVING.toString())
            cell.copy(isServing = false, connectionStatus = ConnectionStatus.NONE)
        }
    }

    /**
     * In NSA, some modems (moto g45, Qualcomm) list the NR leg as an unregistered cell with connection status NONE
     * and no signal; the signal only comes through SignalStrength. When the display override says NSA, a
     * registered LTE cell exists and exactly one NR cell is neither registered nor serving, that cell is labelled
     * SECONDARY_SERVING. Its TAC is dropped when it equals the LTE serving TAC (NSA NR cells broadcast none).
     * Signal is never copied from SignalStrength; that stays in its own row.
     */
    private fun inferNsaSecondary(cells: List<CellSample>, input: ParseInput, warnings: MutableList<ParseWarning>): List<CellSample> {
        if (input.overrideNetworkType !in NSA_OVERRIDES) return cells
        val anchor = cells.firstOrNull { it.source == CellSource.CELL_INFO && it.rat == Rat.LTE && it.isRegistered == true }
            ?: return cells
        val nr = cells.filter { it.source == CellSource.CELL_INFO && it.rat == Rat.NR }
        if (nr.any { it.isServing }) return cells
        val candidates = nr.filter { it.isRegistered != true }
        if (candidates.size > 1) {
            warnings += ParseWarning(WarningCode.NR_NSA_SECONDARY_AMBIGUOUS, Rat.NR, field = "nrCells", rawValue = candidates.size.toString())
            return cells
        }
        val target = candidates.singleOrNull() ?: return cells
        return cells.map { cell ->
            if (cell !== target) return@map cell
            warnings += ParseWarning(WarningCode.NR_NSA_SECONDARY_INFERRED, Rat.NR, cell.pci, "connectionStatus", cell.connectionStatus.rawCode())
            var tac = cell.tac
            if (tac != null && tac == anchor.tac) {
                warnings += ParseWarning(WarningCode.NR_TAC_FROM_LTE, Rat.NR, cell.pci, "tac", tac.toString())
                tac = null
            }
            cell.copy(isServing = true, connectionStatus = ConnectionStatus.SECONDARY_SERVING, tac = tac)
        }
    }

    private fun ConnectionStatus?.rawCode(): String = when (this) {
        ConnectionStatus.NONE -> CONNECTION_NONE.toString()
        ConnectionStatus.PRIMARY_SERVING -> CONNECTION_PRIMARY_SERVING.toString()
        ConnectionStatus.SECONDARY_SERVING -> CONNECTION_SECONDARY_SERVING.toString()
        null -> "null"
    }

    /**
     * Serving-cell signal from SignalStrength.getCellSignalStrengths(): one SIGNAL_STRENGTH row per RAT entry
     * with any valid value. Many phones omit CellInfoNr in NSA, so this is the second source for NR. The rows
     * are serving, have no identity (SignalStrength has no PCI or ARFCN, and none is copied from CellInfo),
     * and their age comes from SignalStrength.getTimestampMillis(), not from the CellInfo timestamp.
     */
    private fun signalStrengthCells(ss: RawSignalStrength?, ctx: Ctx, device: DeviceTraits): List<CellSample> {
        if (ss == null) return emptyList()
        val age = ctx.age(ss.timestampMillis)
        val lte = ss.lte.map { ctx.lteSignalCell(it, age) }
        val nr = ss.nr.map { ctx.nrSignalCell(it, age) }
        return (lte + nr)
            .map { if (device.isSamsung) SamsungQuirks.fixLteSnrZero(it, ctx.warnings) else it }
            .filter { it.hasSignal() }
    }

    private inner class Ctx(val warnings: MutableList<ParseWarning>, private val nowElapsedMs: Long, private val mcc: String?) {

        fun lte(raw: RawLteCell): CellSample {
            val v = Validator(warnings, Rat.LTE, raw.pci)
            val pci = v.int("pci", raw.pci, Ranges.LTE_PCI)
            val earfcn = v.int("earfcn", raw.earfcn, Ranges.EARFCN)
            val (band, bandSource) = band(Rat.LTE, raw.bands, earfcn, Ranges.LTE_BAND, pci)
            val signal = raw.signal
            return base(raw, Rat.LTE).copy(
                cellId = v.long("ci", raw.ci?.toLong(), Ranges.LTE_CI),
                tac = v.int("tac", raw.tac, Ranges.TAC_16),
                pci = pci,
                arfcn = earfcn,
                band = band,
                bandSource = bandSource,
                bandwidthKhz = v.int("bandwidthKhz", raw.bandwidthKhz, Ranges.BANDWIDTH_KHZ),
                mcc = v.mcc(raw.mcc),
                mnc = v.mnc(raw.mnc),
            ).withLteSignal(signal, v)
        }

        fun nr(raw: RawNrCell): CellSample {
            val v = Validator(warnings, Rat.NR, raw.pci)
            val pci = v.int("pci", raw.pci, Ranges.NR_PCI)
            val arfcn = v.int("nrarfcn", raw.nrarfcn, Ranges.NRARFCN)
            val (band, bandSource) = band(Rat.NR, raw.bands, arfcn, Ranges.NR_BAND, pci)
            return base(raw, Rat.NR).copy(
                cellId = v.long("nci", raw.nci, Ranges.NCI),
                tac = v.int("tac", raw.tac, Ranges.TAC_24),
                pci = pci,
                arfcn = arfcn,
                band = band,
                bandSource = bandSource,
                mcc = v.mcc(raw.mcc),
                mnc = v.mnc(raw.mnc),
            ).withNrSignal(raw.signal, v)
        }

        fun wcdma(raw: RawWcdmaCell): CellSample {
            val v = Validator(warnings, Rat.WCDMA, raw.psc)
            return base(raw, Rat.WCDMA).copy(
                cellId = v.long("cid", raw.cid?.toLong(), Ranges.WCDMA_CID),
                tac = v.int("lac", raw.lac, Ranges.TAC_16),
                pci = v.int("psc", raw.psc, Ranges.PSC),
                arfcn = v.int("uarfcn", raw.uarfcn, Ranges.UARFCN),
                mcc = v.mcc(raw.mcc),
                mnc = v.mnc(raw.mnc),
                rscpDbm = v.int("rscp", raw.signal?.dbm, Ranges.RSCP),
                ecNoDb = v.int("ecNo", raw.signal?.ecNo, Ranges.ECNO),
            )
        }

        fun gsm(raw: RawGsmCell): CellSample {
            val v = Validator(warnings, Rat.GSM, raw.bsic)
            return base(raw, Rat.GSM).copy(
                cellId = v.long("cid", raw.cid?.toLong(), Ranges.GSM_CID),
                tac = v.int("lac", raw.lac, Ranges.TAC_16),
                pci = v.int("bsic", raw.bsic, Ranges.BSIC),
                arfcn = v.int("arfcn", raw.arfcn, Ranges.GSM_ARFCN),
                mcc = v.mcc(raw.mcc),
                mnc = v.mnc(raw.mnc),
                rssiDbm = v.int("rssi", raw.signal?.rssi, Ranges.GSM_RSSI),
                timingAdvance = v.int("ta", raw.signal?.timingAdvance, Ranges.GSM_TA),
            )
        }

        fun lteSignalCell(signal: RawLteSignal, age: Long?): CellSample =
            emptyCell(Rat.LTE, CellSource.SIGNAL_STRENGTH, age).withLteSignal(signal, Validator(warnings, Rat.LTE, null))

        fun nrSignalCell(signal: RawNrSignal, age: Long?): CellSample =
            emptyCell(Rat.NR, CellSource.SIGNAL_STRENGTH, age).withNrSignal(signal, Validator(warnings, Rat.NR, null))

        private fun CellSample.withLteSignal(s: RawLteSignal?, v: Validator) = copy(
            rsrpDbm = v.int("rsrp", s?.rsrp, Ranges.LTE_RSRP),
            rsrqDb = v.int("rsrq", s?.rsrq, Ranges.LTE_RSRQ),
            sinrDb = v.int("rssnr", s?.rssnr, Ranges.LTE_RSSNR),
            rssiDbm = v.int("rssi", s?.rssi, Ranges.LTE_RSSI),
            cqi = v.int("cqi", s?.cqi, Ranges.CQI),
            timingAdvance = v.int("ta", s?.timingAdvance, Ranges.LTE_TA),
        )

        private fun CellSample.withNrSignal(s: RawNrSignal?, v: Validator) = copy(
            rsrpDbm = v.int("ssRsrp", s?.ssRsrp, Ranges.NR_RSRP),
            rsrqDb = v.int("ssRsrq", s?.ssRsrq, Ranges.NR_RSRQ),
            sinrDb = v.int("ssSinr", s?.ssSinr, Ranges.NR_SINR),
            csiRsrpDbm = v.int("csiRsrp", s?.csiRsrp, Ranges.NR_RSRP),
            csiRsrqDb = v.int("csiRsrq", s?.csiRsrq, Ranges.NR_RSRQ),
            csiSinrDb = v.int("csiSinr", s?.csiSinr, Ranges.NR_CSI_SINR),
        )

        private fun base(raw: RawCell, rat: Rat): CellSample {
            val status = when (raw.connectionStatus) {
                CONNECTION_NONE -> ConnectionStatus.NONE
                CONNECTION_PRIMARY_SERVING -> ConnectionStatus.PRIMARY_SERVING
                CONNECTION_SECONDARY_SERVING -> ConnectionStatus.SECONDARY_SERVING
                else -> null
            }
            val serving = raw.isRegistered == true ||
                status == ConnectionStatus.PRIMARY_SERVING ||
                status == ConnectionStatus.SECONDARY_SERVING
            return emptyCell(rat, CellSource.CELL_INFO, age(raw.timestampMillis))
                .copy(isServing = serving, isRegistered = raw.isRegistered, connectionStatus = status)
        }

        private fun emptyCell(rat: Rat, source: CellSource, age: Long?) = CellSample(
            rat = rat, isServing = source == CellSource.SIGNAL_STRENGTH, isRegistered = null, connectionStatus = null,
            cellId = null, tac = null, pci = null, arfcn = null, band = null, bandSource = null, bandwidthKhz = null,
            mcc = null, mnc = null, rsrpDbm = null, rsrqDb = null, sinrDb = null, rssiDbm = null, cqi = null,
            timingAdvance = null, csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null,
            ecNoDb = null, source = source, cellInfoAgeMs = age,
        )

        fun age(timestampMillis: Long?): Long? {
            if (timestampMillis == null || timestampMillis <= 0 || timestampMillis == Long.MAX_VALUE) return null
            val age = nowElapsedMs - timestampMillis
            if (age < 0) {
                warnings += ParseWarning(WarningCode.OUT_OF_RANGE, field = "cellTimestampMillis", rawValue = timestampMillis.toString())
                return null
            }
            return age
        }

        /** getBands() first; the mapper only when the API gives nothing. */
        private fun band(rat: Rat, bands: List<Int>?, arfcn: Int?, range: IntRange, pci: Int?): Pair<Int?, BandSource?> {
            val reported = bands.orEmpty()
            val valid = reported.filter { it in range }
            if (valid.size != reported.size) {
                warnings += ParseWarning(WarningCode.INVALID_BAND, rat, pci, "bands", reported.toString())
            }
            if (valid.size > 1) {
                warnings += ParseWarning(WarningCode.MULTIPLE_BANDS, rat, pci, "bands", valid.toString())
            }
            valid.firstOrNull()?.let { return it to BandSource.API }
            val mapped = arfcn?.let { bandMapper.map(rat, it, mcc) } ?: return null to null
            if (mapped.candidates.size > 1) {
                warnings += ParseWarning(WarningCode.MULTIPLE_BANDS, rat, pci, "mappedBands", mapped.candidates.toString())
            }
            return mapped.band to BandSource.MAPPED
        }
    }

    private companion object {
        // CellInfo.CONNECTION_* constants.
        const val CONNECTION_NONE = 0
        const val CONNECTION_PRIMARY_SERVING = 1
        const val CONNECTION_SECONDARY_SERVING = 2

        // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_* values.
        const val OVERRIDE_LTE_CA = 1

        // LTE channel bandwidths, 3GPP TS 36.101 Table 5.6-1.
        val LTE_BANDWIDTHS_KHZ = setOf(1_400, 3_000, 5_000, 10_000, 15_000, 20_000)
        val NSA_OVERRIDES = setOf(3, 4, 5) // NR_NSA, NR_NSA_MMWAVE, NR_ADVANCED
    }
}

internal fun CellSample.hasSignal(): Boolean =
    rsrpDbm != null || rsrqDb != null || sinrDb != null || rssiDbm != null ||
        csiRsrpDbm != null || csiRsrqDb != null || csiSinrDb != null || rscpDbm != null

/** Converts raw values to nullable domain values, recording implausible ones. */
internal class Validator(private val warnings: MutableList<ParseWarning>, private val rat: Rat, rawPci: Int?) {
    // Only for labelling warnings; an invalid PCI is reported separately.
    private val pci = rawPci?.takeIf { it != Int.MAX_VALUE }

    fun int(field: String, raw: Int?, range: IntRange): Int? = when {
        raw == null || raw == Int.MAX_VALUE -> null
        raw !in range -> {
            warnings += ParseWarning(WarningCode.OUT_OF_RANGE, rat, pci, field, raw.toString())
            null
        }
        else -> raw
    }

    fun long(field: String, raw: Long?, range: LongRange): Long? = when {
        raw == null || raw == Long.MAX_VALUE || raw == Int.MAX_VALUE.toLong() -> null
        raw !in range -> {
            warnings += ParseWarning(WarningCode.OUT_OF_RANGE, rat, pci, field, raw.toString())
            null
        }
        else -> raw
    }

    fun mcc(raw: String?): String? = plmnPart("mcc", raw, 3..3)

    fun mnc(raw: String?): String? = plmnPart("mnc", raw, 2..3)

    private fun plmnPart(field: String, raw: String?, length: IntRange): String? = when {
        raw.isNullOrEmpty() -> null
        raw.length in length && raw.all { it in '0'..'9' } -> raw
        else -> {
            warnings += ParseWarning(WarningCode.OUT_OF_RANGE, rat, pci, field, raw)
            null
        }
    }
}

/** Plausibility ranges from the Android CellSignalStrength / CellIdentity docs and 3GPP. */
internal object Ranges {
    val LTE_RSRP = -140..-43
    val LTE_RSRQ = -34..3
    val LTE_RSSNR = -20..30
    val LTE_RSSI = -113..-51
    val CQI = 0..15
    val LTE_TA = 0..1282
    val LTE_PCI = 0..503
    val EARFCN = 0..262_143
    val LTE_CI = 0L..268_435_455L
    val LTE_BAND = 1..255

    val NR_RSRP = -156..-31
    val NR_RSRQ = -43..20
    val NR_SINR = -23..40
    val NR_CSI_SINR = -23..23
    val NR_PCI = 0..1007
    val NRARFCN = 0..3_279_165
    val NCI = 0L..68_719_476_735L
    val NR_BAND = 1..1024

    val TAC_16 = 0..65_535
    val TAC_24 = 0..16_777_215
    val BANDWIDTH_KHZ = 1..400_000

    val RSCP = -120..-24
    val ECNO = -24..1
    val WCDMA_CID = 0L..268_435_455L
    val PSC = 0..511
    val UARFCN = 0..16_383

    val GSM_RSSI = -113..-51
    val GSM_TA = 0..219
    val GSM_CID = 0L..65_535L
    val BSIC = 0..63
    val GSM_ARFCN = 0..1023
}
