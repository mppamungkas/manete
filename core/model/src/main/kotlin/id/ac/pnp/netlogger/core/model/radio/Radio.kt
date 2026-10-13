package id.ac.pnp.netlogger.core.model.radio

import id.ac.pnp.netlogger.core.model.raw.CellInfoOrigin

enum class Rat { LTE, NR, WCDMA, GSM }

enum class CellSource { CELL_INFO, SIGNAL_STRENGTH }

enum class BandSource { API, MAPPED }

enum class ConnectionStatus { NONE, PRIMARY_SERVING, SECONDARY_SERVING }

enum class NetworkType { LTE, NR_NSA, NR_SA, WCDMA, GSM, NONE }

/**
 * One cell at one tick, serving or neighbor. Every field is nullable: OEMs hide or fake values.
 * Fields that do not apply to a RAT stay null (e.g. `cqi` for NR, `rscpDbm` outside WCDMA).
 */
data class CellSample(
    val rat: Rat,
    val isServing: Boolean,
    val isRegistered: Boolean?,
    val connectionStatus: ConnectionStatus?,
    /** LTE ECI (28 bit), NR NCI (36 bit), WCDMA/GSM CID. */
    val cellId: Long?,
    /** TAC for LTE/NR, LAC for WCDMA/GSM. */
    val tac: Int?,
    /** PCI for LTE/NR, PSC for WCDMA, BSIC for GSM. */
    val pci: Int?,
    /** EARFCN / NR-ARFCN / UARFCN / ARFCN. */
    val arfcn: Int?,
    val band: Int?,
    val bandSource: BandSource?,
    val bandwidthKhz: Int?,
    val mcc: String?,
    val mnc: String?,
    /** LTE RSRP or NR SS-RSRP. */
    val rsrpDbm: Int?,
    /** LTE RSRQ or NR SS-RSRQ. */
    val rsrqDb: Int?,
    /** LTE RSSNR (scaling varies by OEM) or NR SS-SINR. */
    val sinrDb: Int?,
    val rssiDbm: Int?,
    val cqi: Int?,
    /** Raw timing advance as reported (LTE: 0..1282, GSM: 0..219). */
    val timingAdvance: Int?,
    val csiRsrpDbm: Int?,
    val csiRsrqDb: Int?,
    val csiSinrDb: Int?,
    val rscpDbm: Int?,
    val ecNoDb: Int?,
    val source: CellSource,
    /** Tick time minus the cell's own timestamp. Large values mean the modem served cached data. */
    val cellInfoAgeMs: Long?,
)

enum class WarningCode {
    /** A getter threw; `rawValue` holds the number of failures. */
    EXTRACTION_FAILED,
    OUT_OF_RANGE,
    INVALID_BAND,
    MULTIPLE_BANDS,
    CELL_INFO_UNAVAILABLE,
    UNSUPPORTED_CELL_TYPE,

    /** Samsung, Android 12+: serving TAC/LAC bytes swapped; the column holds the corrected value. */
    SAMSUNG_TAC_BYTE_SWAP,

    /** Samsung neighbor placeholder (RSSI -51, TA 0, Ec/No 0) replaced by null. */
    SAMSUNG_NEIGHBOR_PLACEHOLDER,

    /** Samsung reports LTE RSSNR 0 for "not available"; replaced by null. */
    SAMSUNG_LTE_SNR_ZERO,
}

/** A value that was dropped or corrected. `rawValue` keeps the original for audit. */
data class ParseWarning(
    val code: WarningCode,
    val rat: Rat? = null,
    val pci: Int? = null,
    val field: String? = null,
    val rawValue: String? = null,
)

/** Raw inputs behind the NR_NSA decision, stored next to the conclusion. */
data class NsaInputs(
    val lteServing: Boolean,
    val nrServingCellInfo: Boolean,
    val nrSignalStrength: Boolean,
    val overrideNetworkType: Int?,
    val dataNetworkType: Int?,
)

/** Everything the radio side knows at one tick. */
data class RadioSnapshot(
    val wallTimeMs: Long,
    val elapsedRealtimeNs: Long,
    val subscriptionId: Int?,
    val simSlot: Int?,
    val operatorName: String?,
    val operatorNumeric: String?,
    val networkType: NetworkType,
    val nsaInputs: NsaInputs,
    val serviceState: Int?,
    val dataState: Int?,
    val isRoaming: Boolean?,
    val cellBandwidthsKhz: List<Int>,
    val cellInfoOrigin: CellInfoOrigin,
    val cells: List<CellSample>,
    val warnings: List<ParseWarning>,
    /** Why the TelephonyCallback could not be registered, if it could not. */
    val listenerError: String?,
) {
    val parseErrors: Int get() = warnings.size
}
