package id.my.pamslab.netlogger.core.telephony.parser

import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.NsaInputs
import id.my.pamslab.netlogger.core.model.radio.Rat

/** Derives the network type from the parsed cells plus the raw callback inputs. Pure. */
object NetworkClassifier {

    // TelephonyManager.NETWORK_TYPE_* values.
    private const val NT_GPRS = 1
    private const val NT_EDGE = 2
    private const val NT_UMTS = 3
    private const val NT_HSDPA = 8
    private const val NT_HSUPA = 9
    private const val NT_HSPA = 10
    private const val NT_LTE = 13
    private const val NT_HSPAP = 15
    private const val NT_GSM = 16
    private const val NT_TD_SCDMA = 17
    private const val NT_LTE_CA = 19
    private const val NT_NR = 20

    // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_* values.
    private const val OVERRIDE_NR_NSA = 3
    private const val OVERRIDE_NR_NSA_MMWAVE = 4
    private const val OVERRIDE_NR_ADVANCED = 5

    // ServiceState.STATE_* values.
    private const val STATE_IN_SERVICE = 0
    private const val STATE_EMERGENCY_ONLY = 2

    fun nsaInputs(
        cells: List<CellSample>,
        nrSignalStrengthPresent: Boolean,
        overrideNetworkType: Int?,
        dataNetworkType: Int?,
    ) = NsaInputs(
        lteServing = cells.any { it.rat == Rat.LTE && it.isServing && it.source == CellSource.CELL_INFO },
        nrServingCellInfo = cells.any { it.rat == Rat.NR && it.isServing && it.source == CellSource.CELL_INFO },
        nrSignalStrength = nrSignalStrengthPresent,
        overrideNetworkType = overrideNetworkType,
        dataNetworkType = dataNetworkType,
    )

    /**
     * NR_NSA: LTE anchor (serving LTE cell or LTE data network type) and any NR evidence: an NR cell, NR
     * signal in SignalStrength, or the NR_NSA / NR_ADVANCED display override.
     */
    fun classify(inputs: NsaInputs, cells: List<CellSample>, serviceState: Int?): NetworkType {
        val dataType = inputs.dataNetworkType
        val servingCells = cells.filter { it.isServing && it.source == CellSource.CELL_INFO }
        val inService = serviceState == null || serviceState == STATE_IN_SERVICE || serviceState == STATE_EMERGENCY_ONLY
        if (!inService && servingCells.isEmpty()) return NetworkType.NONE

        val nrPrimary = servingCells.any { it.rat == Rat.NR && it.connectionStatus == ConnectionStatus.PRIMARY_SERVING }
        val nrEvidence = inputs.nrServingCellInfo || inputs.nrSignalStrength ||
            inputs.overrideNetworkType in setOf(OVERRIDE_NR_NSA, OVERRIDE_NR_NSA_MMWAVE, OVERRIDE_NR_ADVANCED)
        val lteAnchor = inputs.lteServing || dataType == NT_LTE || dataType == NT_LTE_CA

        return when {
            dataType == NT_NR -> NetworkType.NR_SA
            lteAnchor -> if (nrEvidence) NetworkType.NR_NSA else NetworkType.LTE
            nrPrimary -> NetworkType.NR_SA
            dataType in setOf(NT_UMTS, NT_HSDPA, NT_HSUPA, NT_HSPA, NT_HSPAP, NT_TD_SCDMA) ||
                servingCells.any { it.rat == Rat.WCDMA } -> NetworkType.WCDMA
            dataType in setOf(NT_GPRS, NT_EDGE, NT_GSM) || servingCells.any { it.rat == Rat.GSM } -> NetworkType.GSM
            else -> NetworkType.NONE
        }
    }
}
