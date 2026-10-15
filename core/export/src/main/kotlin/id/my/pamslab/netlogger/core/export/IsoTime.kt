package id.my.pamslab.netlogger.core.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** ISO-8601 with milliseconds and offset in one time zone, e.g. 2026-10-14T05:55:32.434+07:00 (UTC: ...Z). */
class IsoTime(val zone: ZoneId) {
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).withZone(zone)

    fun format(epochMs: Long?): String? = epochMs?.let { formatter.format(Instant.ofEpochMilli(it)) }

    fun offset(epochMs: Long): String = zone.rules.getOffset(Instant.ofEpochMilli(epochMs)).id.let { if (it == "Z") "+00:00" else it }
}
