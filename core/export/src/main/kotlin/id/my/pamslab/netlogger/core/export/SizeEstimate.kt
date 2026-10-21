package id.my.pamslab.netlogger.core.export

import java.util.Locale

/**
 * How much space a session takes on the phone and how big its export ZIP gets, estimated from its row counts
 * (Sessions tab). Weights measured on both test phones' databases on 2026-10-16 (A56: 26 sessions, moto: 17):
 * - database, from SQLite's dbstat incl. indexes: measurement 182–189 B, cell 83–90 B, parse_warning ~110 B per row;
 * - export, deflated CSV + GeoJSON per session fitted over 35 sessions: 135 B per tick + 9 B per cell row
 *   (within ±20 % for sessions of 100+ ticks), plus ~3 kB for headers, session.json and the ZIP directory.
 * Capture files are added at their real size (a pcap of mostly encrypted traffic hardly compresses), floor plan images
 * (PNG, already compressed) to the export only: a plan is stored once on the phone, for all sessions that use it.
 */
data class SizeEstimate(val databaseBytes: Long, val captureBytes: Long, val exportBytes: Long) {
    /** On the phone: the session's share of the database plus its capture files. */
    val storageBytes: Long get() = databaseBytes + captureBytes

    companion object {
        const val DB_BYTES_PER_TICK = 185L
        const val DB_BYTES_PER_CELL = 87L
        const val DB_BYTES_PER_WARNING = 110L
        const val ZIP_BYTES_PER_TICK = 135L
        const val ZIP_BYTES_PER_CELL = 9L
        const val ZIP_FIXED_BYTES = 3_000L

        fun of(ticks: Long, cells: Long, warnings: Long, captureBytes: Long = 0, planImageBytes: Long = 0): SizeEstimate = SizeEstimate(
            databaseBytes = ticks * DB_BYTES_PER_TICK + cells * DB_BYTES_PER_CELL + warnings * DB_BYTES_PER_WARNING,
            captureBytes = captureBytes,
            exportBytes = ZIP_FIXED_BYTES + ticks * ZIP_BYTES_PER_TICK + cells * ZIP_BYTES_PER_CELL + captureBytes + planImageBytes,
        )

        /** "820 B", "12 kB", "4.6 MB", "1.2 GB" (decimal units, like Android's storage settings). */
        fun format(bytes: Long): String = when {
            bytes < 1_000 -> "$bytes B"
            bytes < 1_000_000 -> "%.0f kB".format(Locale.ROOT, bytes / 1e3)
            bytes < 1_000_000_000 -> (if (bytes < 10_000_000) "%.1f MB" else "%.0f MB").format(Locale.ROOT, bytes / 1e6)
            else -> "%.1f GB".format(Locale.ROOT, bytes / 1e9)
        }
    }
}
