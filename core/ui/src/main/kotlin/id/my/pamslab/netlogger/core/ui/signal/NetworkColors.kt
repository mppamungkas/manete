package id.my.pamslab.netlogger.core.ui.signal

import androidx.compose.ui.graphics.Color

/** Color per networkType, shared by the map and the session charts. Unknown types are gray. */
object NetworkColors {
    val ARGB: Map<String, Long> = linkedMapOf(
        "NR_SA" to 0xFFD81B60,
        "NR_NSA" to 0xFF8E24AA,
        "LTE" to 0xFF1E88E5,
        "WCDMA" to 0xFF00897B,
        "GSM" to 0xFF6D4C41,
        "NONE" to SignalLevel.NONE.argb,
    )

    fun color(networkType: String?): Color = Color(ARGB[networkType] ?: SignalLevel.NONE.argb)

    fun hex(networkType: String): String = "#%06X".format((ARGB[networkType] ?: SignalLevel.NONE.argb) and 0xFFFFFF)
}
