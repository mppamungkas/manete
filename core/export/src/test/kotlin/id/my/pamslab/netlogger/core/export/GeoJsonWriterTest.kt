package id.my.pamslab.netlogger.core.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class GeoJsonWriterTest {

    private fun write(fixes: List<Pair<Double?, Double?>>, events: List<id.my.pamslab.netlogger.core.data.db.EventEntity> = emptyList()): List<JsonObject> {
        val sb = StringBuilder()
        val w = GeoJsonWriter(sb, IsoTime(ZoneId.of("Asia/Jakarta")), "u-1")
        fixes.forEachIndexed { i, (lat, lon) -> w.point(lat, lon, START_MS + i * 1_000L) }
        w.finish(events)
        val root = Json.parseToJsonElement(sb.toString()).jsonObject
        assertEquals("FeatureCollection", root["type"]?.jsonPrimitive?.content)
        return root.getValue("features").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.geometry() = getValue("geometry").jsonObject
    private fun JsonObject.prop(name: String) = getValue("properties").jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun `track is a lon-lat LineString, ticks without a fix skipped, markers are Points`() {
        val features = write(
            listOf(-0.9 to 100.4, null to null, -0.90001 to 100.40002, Double.NaN to 1.0),
            listOf(marker(1), marker(2, lat = null, lon = null)),
        )
        assertEquals(2, features.size)
        val track = features[0]
        assertEquals("LineString", track.geometry()["type"]?.jsonPrimitive?.content)
        assertEquals("[[100.4,-0.9],[100.40002,-0.90001]]", track.geometry().getValue("coordinates").toString())
        assertEquals("2", track.prop("points"))
        assertEquals("2026-10-14T05:40:00.000+07:00", track.prop("start"))
        assertEquals("2026-10-14T05:40:02.000+07:00", track.prop("end"))

        val m = features[1]
        assertEquals("Point", m.geometry()["type"]?.jsonPrimitive?.content)
        assertEquals("[100.4,-0.9]", m.geometry().getValue("coordinates").toString())
        assertEquals("USER_MARKER", m.prop("type"))
        assertEquals("2026-10-14T05:40:01.000+07:00", m.prop("time"))
    }

    @Test
    fun `one fix is a Point, no fix and no events is an empty collection`() {
        val one = write(listOf(-0.9 to 100.4))
        assertEquals("Point", one.single().geometry()["type"]?.jsonPrimitive?.content)
        assertEquals("track", one.single().prop("kind"))
        assertEquals(emptyList<JsonObject>(), write(listOf(null to null)))
        assertEquals(1, write(emptyList(), listOf(marker(1))).size)
    }
}
