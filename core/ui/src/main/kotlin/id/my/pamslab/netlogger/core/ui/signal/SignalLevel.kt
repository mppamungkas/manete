package id.my.pamslab.netlogger.core.ui.signal

import androidx.compose.ui.graphics.Color

/** Color class of one signal value. [argb] is shared by Compose and the map renderer. */
enum class SignalLevel(val argb: Long) {
    GOOD(0xFF2E7D32),
    FAIR(0xFFFDD835),
    POOR(0xFFFB8C00),
    BAD(0xFFC62828),
    NONE(0xFF9E9E9E),
    ;

    val color: Color get() = Color(argb)

    /** "#RRGGBB" for map styles. */
    val hex: String get() = "#%06X".format(argb and 0xFFFFFF)

    companion object {
        /**
         * LTE RSRP / NR SS-RSRP (CLAUDE.md): > -80 green, -80 … -95 yellow, -96 … -110 orange, < -110 red, null gray.
         */
        fun rsrp(dbm: Int?): SignalLevel = when {
            dbm == null -> NONE
            dbm > -80 -> GOOD
            dbm >= -95 -> FAIR
            dbm >= -110 -> POOR
            else -> BAD
        }

        /** LTE RSSNR / NR SS-SINR (agreed 2026-10-14): >= 20 green, 13 … < 20 yellow, 0 … < 13 orange, < 0 red, null gray. */
        fun sinr(db: Int?): SignalLevel = when {
            db == null -> NONE
            db >= 20 -> GOOD
            db >= 13 -> FAIR
            db >= 0 -> POOR
            else -> BAD
        }
    }
}
