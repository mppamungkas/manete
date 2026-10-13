package id.my.pamslab.netlogger.core.export

import id.my.pamslab.netlogger.core.data.db.EventEntity
import kotlinx.serialization.json.JsonPrimitive

/**
 * Streams an RFC 7946 FeatureCollection: one LineString of the session's GPS fixes (2D, [lon, lat]) followed by one
 * Point per event that has a location. Ticks without a fix are skipped, never written as 0/0. Call [point] for every
 * fix in order, then [finish] with the events.
 */
class GeoJsonWriter(private val out: Appendable, private val time: IsoTime, private val sessionUuid: String) {
    private var points = 0
    private var firstMs: Long? = null
    private var lastMs: Long? = null
    private var pendingFirst: Pair<Double, Double>? = null

    init {
        out.append("""{"type":"FeatureCollection","features":[""")
    }

    fun point(lat: Double?, lon: Double?, wallTimeMs: Long) {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return
        if (points == 0) {
            pendingFirst = lon to lat
            firstMs = wallTimeMs
        } else {
            if (points == 1) {
                out.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
                pendingFirst?.let { position(it.first, it.second) }
            }
            out.append(',')
            position(lon, lat)
        }
        points++
        lastMs = wallTimeMs
    }

    fun finish(events: List<EventEntity>) {
        var first: Boolean
        when (points) {
            0 -> first = true
            1 -> {
                // A single fix is not a line: write it as a Point.
                out.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":""")
                pendingFirst?.let { position(it.first, it.second) }
                out.append("},")
                trackProperties()
                first = false
            }
            else -> {
                out.append("]},")
                trackProperties()
                first = false
            }
        }
        events.filter { it.lat != null && it.lon != null }.forEach { e ->
            if (!first) out.append(',')
            first = false
            out.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":""")
            position(e.lon ?: 0.0, e.lat ?: 0.0)
            out.append("""},"properties":{"kind":"event","type":""").append(str(e.type))
                .append(""","time":""").append(str(time.format(e.wallTimeMs)))
                .append(""","wallTimeMs":""").append(e.wallTimeMs.toString())
                .append(""","elapsedRealtimeNs":""").append(e.elapsedRealtimeNs.toString())
                .append(""","fromValue":""").append(str(e.fromValue))
                .append(""","toValue":""").append(str(e.toValue))
                .append("}}")
        }
        out.append("]}")
    }

    private fun trackProperties() {
        out.append(""""properties":{"kind":"track","sessionUuid":""").append(str(sessionUuid))
            .append(""","points":""").append(points.toString())
            .append(""","start":""").append(str(time.format(firstMs)))
            .append(""","end":""").append(str(time.format(lastMs)))
            .append("}}")
    }

    private fun position(lon: Double, lat: Double) {
        out.append('[').append(csvText(lon)).append(',').append(csvText(lat)).append(']')
    }

    private fun str(s: String?): String = JsonPrimitive(s).toString()
}
