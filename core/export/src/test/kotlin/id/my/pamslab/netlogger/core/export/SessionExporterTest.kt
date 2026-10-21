package id.my.pamslab.netlogger.core.export

import id.my.pamslab.netlogger.core.data.CaptureFileStore
import id.my.pamslab.netlogger.core.data.GeorefJson
import id.my.pamslab.netlogger.core.data.PlanFileStore
import id.my.pamslab.netlogger.core.model.indoor.GeoPoint
import id.my.pamslab.netlogger.core.model.indoor.Georeference
import id.my.pamslab.netlogger.core.data.db.FloorPlanEntity
import id.my.pamslab.netlogger.core.data.db.PlanWaypointEntity
import id.my.pamslab.netlogger.core.data.db.ThroughputTestEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.zip.ZipInputStream

class SessionExporterTest {

    private val zone = ZoneId.of("Asia/Jakarta")

    private suspend fun exportZip(
        dao: FakeExportDao,
        captures: CaptureFileStore? = null,
        plans: PlanFileStore? = null,
        includePlanImages: Boolean = true,
        heatmaps: HeatmapRenderer? = null,
    ): Pair<ExportResult, Map<String, String>> {
        val out = ByteArrayOutputStream()
        val result = SessionExporter(dao, captures, plans, heatmaps).export(1, out, zone, nowMs = START_MS + 3_600_000, includePlanImages = includePlanImages)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return result to entries
    }

    private fun lines(csv: String) = csv.split("\r\n").dropLast(1)

    /** 700 ticks; taps at ticks 10, 20 (removed), 30 on plan 1, then 600 and 610 on plan 2 (another floor). */
    private fun indoorDao(georefOne: String? = null): FakeExportDao {
        fun tap(seq: Long, plan: Long, x: Double, removed: Boolean = false) = PlanWaypointEntity(
            sessionId = 1, planId = plan, wallTimeMs = START_MS + seq * 1_000, elapsedRealtimeNs = START_NS + seq * 1_000_000_000,
            x = x, y = 100.0, kind = "HERE", removed = removed,
        )
        return FakeExportDao(
            measurements = (0L until 700).map { measurement(it, lat = null, lon = null) },
            waypoints = listOf(tap(10, 1, 0.0), tap(20, 1, 999.0, removed = true), tap(30, 1, 200.0), tap(600, 2, 0.0), tap(610, 2, 50.0)),
            plans = listOf(
                FloorPlanEntity(1, "p-one", "Gedung C", "Lantai 2", "p-one.png", 3000, 2000, 0.05, georefOne, 0),
                FloorPlanEntity(2, "p-two", "Gedung C", null, "p-two.png", 1000, 800, null, null, 0),
            ),
        )
    }

    @Test
    fun `indoor session - taps, placed ticks, plans and images`() = runTest {
        val dir = Files.createTempDirectory("plans").toFile()
        File(dir, "p-one.png").writeText("PNG-ONE")
        File(dir, "p-two.png").writeText("PNG-TWO")
        val store = object : PlanFileStore { override val dir = dir }
        val (result, files) = exportZip(indoorDao(), plans = store)

        val taps = lines(files.getValue("waypoints.csv"))
        assertEquals(Columns(IsoTime(zone)).waypoints.joinToString(",") { it.name }, taps[0])
        assertEquals("every tap, the undone one too", 6, taps.size)
        assertTrue(taps[2], taps[2].endsWith(",p-one,Gedung C,Lantai 2,999,100,49.95,5,HERE,true"))

        val indoor = lines(files.getValue("indoor.csv"))
        assertEquals("ticks 10..30 on floor 1, 600..610 on floor 2; none in between", 1 + 21 + 11, indoor.size)
        val tick20 = indoor.first { it.startsWith("20,") }.split(',')
        assertEquals("the undone tap is not used: halfway between 0 and 200", "100", tick20[6])
        assertEquals("5", tick20[8]) // 100 px × 0.05 m
        assertEquals("10", tick20[10]) // seconds to the nearest tap
        val tick605 = indoor.first { it.startsWith("605,") }.split(',')
        assertEquals(listOf("p-two", "", "25", "100", "", ""), tick605.subList(4, 10))

        assertEquals("PNG-ONE", files["plans/p-one.png"])
        assertEquals("PNG-TWO", files["plans/p-two.png"])
        val plans = Json.parseToJsonElement(files.getValue("plans.json")).toString()
        assertTrue(plans, plans.contains("\"image\":\"plans/p-one.png\"") && plans.contains("\"taps\":2"))
        assertEquals(5L, result.counts.waypoints)
        assertEquals(32L, result.counts.indoorTicks)
        val listed = Json.parseToJsonElement(files.getValue("session.json")).jsonObject.getValue("files").toString()
        listOf("waypoints.csv", "indoor.csv", "plans.json", "plans/p-one.png").forEach { assertTrue(it, listed.contains(it)) }
        dir.deleteRecursively()
    }

    /** Plan 1 placed north-up: pixel (0, 0) at -0.9 / 100.4, 0.05 m per pixel. */
    private val northUp = GeorefJson.encode(
        Georeference(
            listOf(
                GeoPoint(0.0, 0.0, -0.9, 100.4),
                GeoPoint(2000.0, 0.0, -0.9, 100.4 + 100.0 / (6_371_008.8 * Math.PI / 180 * Math.cos(Math.toRadians(-0.9)))),
            ),
        ),
    )

    @Test
    fun `a plan placed on the map gives lat and lon, and an indoor line in track geojson`() = runTest {
        val (_, files) = exportZip(indoorDao(georefOne = northUp))
        val tick20 = lines(files.getValue("indoor.csv")).first { it.startsWith("20,") }.split(',')
        // Pixel (100, 100) at 0.05 m per pixel, north up: 5 m east and 5 m south of the anchor.
        val mPerDeg = 6_371_008.8 * Math.PI / 180
        assertEquals(-0.9 - 5 / mPerDeg, tick20[11].toDouble(), 1e-7)
        assertEquals(100.4 + 5 / (mPerDeg * Math.cos(Math.toRadians(-0.9))), tick20[12].toDouble(), 1e-7)
        val onFloor2 = lines(files.getValue("indoor.csv")).first { it.startsWith("605,") }.split(',')
        assertEquals("floor 2 is not placed", listOf("", ""), onFloor2.subList(11, 13))
        val geo = files.getValue("track.geojson")
        assertTrue(geo, geo.contains("\"kind\":\"indoor\",\"positionSource\":\"FLOOR_PLAN\",\"planUuid\":\"p-one\",\"floor\":\"Lantai 2\",\"points\":21"))
        assertTrue(files.getValue("plans.json").contains("\"rotationDeg\": 0.0"))
    }

    @Test
    fun `heatmaps per plan and metric, only with the images`() = runTest {
        val dir = Files.createTempDirectory("plans").toFile()
        File(dir, "p-one.png").writeText("PNG-ONE")
        File(dir, "p-two.png").writeText("PNG-TWO")
        val store = object : PlanFileStore { override val dir = dir }
        val calls = mutableListOf<Pair<String, Int>>()
        val renderer = object : HeatmapRenderer {
            override fun render(
                image: File, widthPx: Int, heightPx: Int, title: String, metric: HeatmapMetric,
                points: List<HeatmapPoint>, path: List<Pair<Double, Double>>, out: java.io.OutputStream,
            ): Boolean {
                calls += title to points.size
                out.write("HEAT".toByteArray())
                return metric != HeatmapMetric.NR_SINR || image.name != "p-two.png" // one drawing fails
            }
        }
        val (_, files) = exportZip(indoorDao(), plans = store, heatmaps = renderer)
        assertTrue(calls.toString(), calls.contains("LTE RSRP · Gedung C · Lantai 2" to 21))
        assertTrue(calls.toString(), calls.contains("LTE RSRP · Gedung C" to 11))
        assertEquals("HEAT", files["heatmaps/p-one_lte_rsrp.png"])
        assertTrue("a failed drawing leaves no entry", "heatmaps/p-two_nr_sinr.png" !in files)
        assertTrue(files.getValue("session.json").contains("heatmaps/p-one_lte_rsrp.png"))

        calls.clear()
        val (_, without) = exportZip(indoorDao(), plans = store, heatmaps = renderer, includePlanImages = false)
        assertTrue("no images, no heatmaps", calls.isEmpty() && without.keys.none { it.startsWith("heatmaps/") })
        dir.deleteRecursively()
    }

    @Test
    fun `plan images can be left out, positions stay`() = runTest {
        val dir = Files.createTempDirectory("plans").toFile()
        File(dir, "p-one.png").writeText("PNG-ONE")
        val (_, files) = exportZip(indoorDao(), plans = object : PlanFileStore { override val dir = dir }, includePlanImages = false)
        assertTrue(files.keys.none { it.startsWith("plans/") })
        assertTrue(files.getValue("plans.json").contains("\"image\": null"))
        assertEquals(33, lines(files.getValue("indoor.csv")).size)
        dir.deleteRecursively()
    }

    @Test
    fun `a session without taps has no indoor files`() = runTest {
        val (_, files) = exportZip(FakeExportDao())
        assertTrue(files.keys.none { it in setOf("waypoints.csv", "indoor.csv", "plans.json") })
    }

    @Test
    fun `bundle has every file, one header plus one row per record, across page boundaries`() = runTest {
        val ticks = (0L until 1_201).map { measurement(it, lat = if (it == 5L) null else -0.9, lon = if (it == 5L) null else 100.4) }
        val dao = FakeExportDao(
            measurements = ticks,
            events = listOf(marker(3)),
            warnings = listOf(warning("NR_SERVING_STRONGEST", "2"), warning("OUT_OF_RANGE", "a,\"b\"")),
            throughput = listOf(
                ThroughputTestEntity(
                    sessionId = 1, startedAtMs = START_MS + 5_000, startedElapsedNs = 5, type = "DOWNLOAD",
                    serverUrl = "https://speed.example.org/backend/garbage.php?ckSize=100", durationMs = 10_000,
                    bytes = 52_428_800, mbps = 41.94304, streams = 4, rttMinMs = null, rttAvgMs = null, rttMaxMs = null,
                    jitterMs = null, lossPct = null, captureActive = false, error = null, triggeredBy = "PERIODIC",
                ),
            ),
        )
        val (result, files) = exportZip(dao)

        assertEquals(SessionExporter.FILES.toSet(), files.keys)
        assertEquals(1_202, lines(files.getValue("measurements.csv")).size)
        assertEquals(1_202, lines(files.getValue("flat.csv")).size)
        assertEquals(1 + 1_201 * a56Cells().size, lines(files.getValue("cells.csv")).size)
        assertEquals(2, lines(files.getValue("events.csv")).size)
        assertEquals(3, lines(files.getValue("warnings.csv")).size)
        assertTrue(files.getValue("warnings.csv").contains(",\"a,\"\"b\"\"\","))
        assertEquals(SessionJson.Counts(1_201, 1_201L * a56Cells().size, 1, 2, 1_200, 1), result.counts)
        val throughput = lines(files.getValue("throughput.csv"))
        assertEquals(2, throughput.size)
        assertTrue(throughput[1].contains(",DOWNLOAD,https://speed.example.org/backend/garbage.php?ckSize=100,,PERIODIC,10000,52428800,41.94304,4,,,,,,false,"))
        assertTrue("paged, not one query", dao.measurementQueries >= 3)

        val seqs = lines(files.getValue("measurements.csv")).drop(1).map { it.substringBefore(',').toLong() }
        assertEquals("no tick lost or repeated at page edges", (0L until 1_201).toList(), seqs)
        val header = lines(files.getValue("measurements.csv")).first().split(',')
        val row5 = lines(files.getValue("measurements.csv"))[6].split(',')
        assertEquals("", row5[header.indexOf("lat")])
        assertEquals("WAITING", row5[header.indexOf("locationStatus")])
        assertTrue("JSON list with a comma is quoted", lines(files.getValue("measurements.csv"))[6].contains(",\"[20000,60000]\","))
    }

    @Test
    fun `a traffic capture is copied into the bundle and listed`() = runTest {
        val dir = Files.createTempDirectory("captures").toFile()
        val store = object : CaptureFileStore {
            override fun pcap(sessionUuid: String) = File(dir, "$sessionUuid.pcap")
            override fun sync(sessionUuid: String) = File(dir, "${sessionUuid}_sync.csv")
            override fun connections(sessionUuid: String) = File(dir, "${sessionUuid}_connections.csv")
        }
        val uuid = session().uuid
        store.pcap(uuid).writeText("pcap-bytes")
        store.sync(uuid).writeText("packet,arrivalElapsedNs\n")
        store.connections(uuid).writeText("protocol,ipVersion\r\nTCP,4\r\n")
        val (_, files) = exportZip(FakeExportDao(session = session().copy(captureFile = "$uuid.pcap")), store)
        assertEquals("pcap-bytes", files.getValue("capture.pcap"))
        assertEquals("packet,arrivalElapsedNs\n", files.getValue("capture_sync.csv"))
        assertEquals("protocol,ipVersion\r\nTCP,4\r\n", files.getValue("connections.csv"))
        val json = Json.parseToJsonElement(files.getValue("session.json")).jsonObject
        assertEquals("$uuid.pcap", json["captureFile"]?.jsonPrimitive?.content)
        assertTrue(json.getValue("files").toString().contains("capture.pcap"))
        assertTrue(json.getValue("files").toString().contains("connections.csv"))
        dir.deleteRecursively()

        val (_, without) = exportZip(FakeExportDao(), store)
        assertEquals("no capture: no entry", SessionExporter.FILES.toSet(), without.keys)
    }

    @Test
    fun `session json keeps metadata, zone and UTF-8 text`() = runTest {
        val (result, files) = exportZip(FakeExportDao())
        val json = Json.parseToJsonElement(files.getValue("session.json")).jsonObject
        assertEquals("netlogger-session", json["format"]?.jsonPrimitive?.content)
        assertEquals("cb1784a9-879e-4a6e-9959-31f7b7d9821c", json["uuid"]?.jsonPrimitive?.content)
        assertEquals("Pasar Raya, loop 1 \"A\"", json["label"]?.jsonPrimitive?.content)
        assertEquals("Ümlaut, comma\nsecond line", json["notes"]?.jsonPrimitive?.content)
        assertEquals("Asia/Jakarta", json["timeZone"]?.jsonPrimitive?.content)
        assertEquals("+07:00", json["utcOffset"]?.jsonPrimitive?.content)
        assertEquals("2026-10-14T05:40:00.000+07:00", json["startedAt"]?.jsonPrimitive?.content)
        assertEquals("60000", json["durationMs"]?.jsonPrimitive?.content)
        assertEquals("89", json.getValue("network").jsonObject["mnc"]?.jsonPrimitive?.content)
        assertEquals("netlogger_SM-A566B_20261014-054000_Pasar-Raya-loop-1-A_cb1784a9.zip", result.fileName)
    }

    @Test
    fun `flat csv in the bundle follows the source rule`() = runTest {
        val (_, files) = exportZip(FakeExportDao())
        val rows = lines(files.getValue("flat.csv"))
        val header = rows.first().split(',')
        val first = rows[1]
        // cellBandwidthsJson is quoted and contains a comma; split around it.
        val cells = first.replace("\"[20000,60000]\"", "BW").split(',')
        assertEquals("15", cells[header.indexOf("lteSinr")])
        assertEquals("SIGNAL_STRENGTH", cells[header.indexOf("lteSinrSource")])
        assertEquals("440", cells[header.indexOf("nrPci")])
    }

    @Test
    fun `progress goes through every file and ends at 1`() = runTest {
        val dir = Files.createTempDirectory("captures").toFile()
        val store = object : CaptureFileStore {
            override fun pcap(sessionUuid: String) = File(dir, "$sessionUuid.pcap")
            override fun sync(sessionUuid: String) = File(dir, "${sessionUuid}_sync.csv")
            override fun connections(sessionUuid: String) = File(dir, "${sessionUuid}_connections.csv")
        }
        store.pcap(session().uuid).writeBytes(ByteArray(200_000))
        val dao = FakeExportDao(measurements = (0L until 1_201).map { measurement(it) })
        val seen = mutableListOf<ExportProgress>()
        SessionExporter(dao, store).export(1, ByteArrayOutputStream(), zone, onProgress = { seen += it })
        dir.deleteRecursively()

        assertEquals(listOf("measurements.csv", "cells.csv", "flat.csv", "track.geojson", "capture.pcap"), seen.map { it.file }.distinct())
        assertTrue("never goes back", seen.zipWithNext().all { (a, b) -> b.fraction >= a.fraction })
        assertEquals(1.0, seen.last().fraction, 1e-9)
        // The four tick passes and the capture each take a fifth; every pass ends at its share.
        listOf("measurements.csv" to 0.2, "cells.csv" to 0.4, "flat.csv" to 0.6, "track.geojson" to 0.8).forEach { (file, end) ->
            assertEquals(file, end, seen.last { it.file == file }.fraction, 1e-9)
        }
        assertEquals("1201 ticks in pages of 500", 3, seen.count { it.file == "measurements.csv" })
    }

    @Test
    fun `a recording session is not exported`() = runTest {
        try {
            exportZip(FakeExportDao(session = session(endedAt = null)))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("session is still recording", e.message)
        }
    }

    @Test
    fun `file name falls back to the scenario and stays ASCII`() {
        assertEquals("netlogger_SM-A566B_20261014-054000_walk_cb1784a9.zip", SessionExporter.fileName(session(label = "  "), zone))
        assertEquals("netlogger_SM-A566B_20261014-054000_x_cb1784a9.zip", SessionExporter.fileName(session(label = "日本"), zone))
    }
}
