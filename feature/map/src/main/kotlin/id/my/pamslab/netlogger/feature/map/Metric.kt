package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.ui.signal.NetworkColors
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel

/** What colors the track. */
enum class Metric(val label: String) {
    LTE_RSRP("LTE RSRP"),
    NR_RSRP("NR SS-RSRP"),
    LTE_SINR("LTE SINR"),
    NR_SINR("NR SS-SINR"),
    NETWORK_TYPE("Network"),
    ;

    fun hex(p: TrackPoint): String = when (this) {
        LTE_RSRP -> SignalLevel.rsrp(p.lteRsrp).hex
        NR_RSRP -> SignalLevel.rsrp(p.nrRsrp).hex
        LTE_SINR -> SignalLevel.sinr(p.lteSinr).hex
        NR_SINR -> SignalLevel.sinr(p.nrSinr).hex
        NETWORK_TYPE -> NetworkColors.hex(p.networkType)
    }

    /** Legend rows: color and text. */
    fun legend(): List<Pair<Long, String>> = when (this) {
        LTE_RSRP, NR_RSRP -> listOf(
            SignalLevel.GOOD.argb to "> -80 dBm",
            SignalLevel.FAIR.argb to "-80 … -95",
            SignalLevel.POOR.argb to "-96 … -110",
            SignalLevel.BAD.argb to "< -110",
            SignalLevel.NONE.argb to "none",
        )
        LTE_SINR, NR_SINR -> listOf(
            SignalLevel.GOOD.argb to "≥ 20 dB",
            SignalLevel.FAIR.argb to "13 … 19",
            SignalLevel.POOR.argb to "0 … 12",
            SignalLevel.BAD.argb to "< 0",
            SignalLevel.NONE.argb to "none",
        )
        NETWORK_TYPE -> NetworkColors.ARGB.entries.map { it.value to it.key }
    }
}
