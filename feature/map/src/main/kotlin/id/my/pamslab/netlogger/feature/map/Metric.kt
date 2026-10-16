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

    fun hex(p: TrackPoint): String = "#%06X".format(argb(p) and 0xFFFFFF)

    /** The point's colour, which is also its legend category. */
    fun argb(p: TrackPoint): Long = when (this) {
        LTE_RSRP -> SignalLevel.rsrp(p.lteRsrp).argb
        NR_RSRP -> SignalLevel.rsrp(p.nrRsrp).argb
        LTE_SINR -> SignalLevel.sinr(p.lteSinr).argb
        NR_SINR -> SignalLevel.sinr(p.nrSinr).argb
        NETWORK_TYPE -> NetworkColors.ARGB[p.networkType] ?: SignalLevel.NONE.argb
    }

    /** Legend rows with the number of [points] in each: colour, text, count. */
    fun legendCounts(points: List<TrackPoint>): List<Triple<Long, String, Int>> {
        val counts = points.groupingBy(::argb).eachCount()
        return legend().map { (argb, text) -> Triple(argb, text, counts[argb] ?: 0) }
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
