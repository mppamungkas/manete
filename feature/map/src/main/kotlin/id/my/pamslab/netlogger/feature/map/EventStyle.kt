package id.my.pamslab.netlogger.feature.map

/**
 * How an event is drawn on the map. User markers are large filled dots; detected events are small white rings, so
 * they stay apart from the filled, metric-colored track points. Ring color by kind: handover dark gray, network type /
 * NR leg purple, loss of service, GPS or a data-SIM switch red.
 */
data class EventStyle(val fill: String, val stroke: String, val radius: Float, val strokeWidth: Float) {
    companion object {
        private val MARKER = EventStyle("#000000", "#FFFFFF", 7f, 2f)
        private fun ring(color: String) = EventStyle("#FFFFFF", color, 4.5f, 2.5f)

        fun of(type: String): EventStyle = when (type) {
            "USER_MARKER" -> MARKER
            "PCI_CHANGE_LTE", "PCI_CHANGE_NR" -> ring("#212121")
            "RAT_CHANGE", "NR_ADDED", "NR_REMOVED" -> ring("#8E24AA")
            else -> ring("#C62828")
        }
    }
}
