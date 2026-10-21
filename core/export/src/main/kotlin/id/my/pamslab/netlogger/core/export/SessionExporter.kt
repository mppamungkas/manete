package id.my.pamslab.netlogger.core.export

import id.my.pamslab.netlogger.core.data.CaptureFileStore
import id.my.pamslab.netlogger.core.data.PlanFileStore
import id.my.pamslab.netlogger.core.data.db.ExportDao
import id.my.pamslab.netlogger.core.data.db.MeasurementEntity
import id.my.pamslab.netlogger.core.data.db.SessionEntity
import id.my.pamslab.netlogger.core.model.indoor.PlanInterpolator
import id.my.pamslab.netlogger.core.model.indoor.Waypoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

data class ExportResult(val fileName: String, val counts: SessionJson.Counts)

/** How far an export is: the file being written and the share of the whole export done (0..1). */
data class ExportProgress(val file: String, val fraction: Double)

/**
 * Writes one session as a ZIP bundle: session.json, measurements.csv, cells.csv, events.csv, warnings.csv, throughput.csv, flat.csv and
 * track.geojson; with floor plan taps (decision 006) also waypoints.csv, indoor.csv, plans.json and the plan images.
 * Ticks are read in pages of [PAGE_TICKS], so memory stays flat for long sessions. Timestamps are formatted in [zone]
 * (the device zone at export; sessions do not store one) next to the raw epoch ms and elapsedRealtime ns columns.
 */
class SessionExporter @Inject constructor(
    private val dao: ExportDao,
    /** Traffic captures; none in tests. */
    private val captures: CaptureFileStore? = null,
    /** Floor plan images; none in tests. */
    private val plans: PlanFileStore? = null,
) {

    suspend fun fileName(sessionId: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val s = requireNotNull(dao.session(sessionId)) { "session $sessionId not found" }
        return fileName(s, zone)
    }

    /**
     * Throws IllegalArgumentException for a missing or still-recording session; [out] is not closed. [onProgress] is
     * called after every page of ticks and capture chunk: the four tick passes (measurements, cells, flat, track) and
     * the capture files share the whole equally. [includePlanImages] false leaves the floor plan images out (decision
     * 006, D8: a building's layout can be confidential); the positions are exported either way.
     */
    suspend fun export(
        sessionId: Long,
        out: OutputStream,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
        includePlanImages: Boolean = true,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        val session = requireNotNull(dao.session(sessionId)) { "session $sessionId not found" }
        require(session.endedAt != null) { "session is still recording" }
        val time = IsoTime(zone)
        val columns = Columns(time)
        val zip = ZipOutputStream(out)
        val captureFiles = captures?.let { store ->
            listOfNotNull(
                store.pcap(session.uuid).takeIf { it.isFile }?.let { CAPTURE to it },
                store.sync(session.uuid).takeIf { it.isFile }?.let { CAPTURE_SYNC to it },
                store.connections(session.uuid).takeIf { it.isFile }?.let { CONNECTIONS to it },
            )
        }.orEmpty()
        val totalTicks = dao.measurementCount(sessionId).coerceAtLeast(1)
        val parts = TICK_PASSES + if (captureFiles.isEmpty()) 0 else 1
        var pass = 0
        fun ticksDone(file: String, done: Long) = onProgress(ExportProgress(file, (pass + done.toDouble() / totalTicks) / parts))

        var measurements = 0L
        var cells = 0L
        entry(zip, MEASUREMENTS) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.measurements.map { it.name })
            pages(sessionId) { page ->
                csv.writeAll(columns.measurements, page)
                measurements += page.size
                ticksDone(MEASUREMENTS, measurements)
            }
        }
        pass++
        entry(zip, CELLS) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.cells.map { it.name })
            var done = 0L
            pages(sessionId) { page ->
                val rows = dao.cells(sessionId, page.first().seq, page.last().seq)
                csv.writeAll(columns.cells, rows)
                cells += rows.size
                done += page.size
                ticksDone(CELLS, done)
            }
        }
        pass++
        entry(zip, FLAT) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.flat.map { it.name })
            var done = 0L
            pages(sessionId) { page ->
                val bySeq = dao.cells(sessionId, page.first().seq, page.last().seq).groupBy({ it.seq }, { it.cell })
                csv.writeAll(columns.flat, page.map { FlatRow.of(it, bySeq[it.seq].orEmpty()) })
                done += page.size
                ticksDone(FLAT, done)
            }
        }
        pass++
        val events = dao.events(sessionId)
        entry(zip, EVENTS) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.events.map { it.name })
            csv.writeAll(columns.events, events)
        }
        val warnings = dao.warnings(sessionId)
        entry(zip, WARNINGS) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.warnings.map { it.name })
            csv.writeAll(columns.warnings, warnings)
        }
        val throughput = dao.throughputTests(sessionId)
        entry(zip, THROUGHPUT) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.throughput.map { it.name })
            csv.writeAll(columns.throughput, throughput)
        }
        var trackPoints = 0L
        entry(zip, TRACK) { w ->
            val geo = GeoJsonWriter(w, time, session.uuid)
            var done = 0L
            pages(sessionId) { page ->
                page.forEach { m ->
                    if (m.lat != null && m.lon != null) trackPoints++
                    geo.point(m.lat, m.lon, m.wallTimeMs)
                }
                done += page.size
                ticksDone(TRACK, done)
            }
            geo.finish(events)
        }
        pass++
        val indoor = exportIndoor(zip, sessionId, columns, includePlanImages)
        // Traffic capture written by NetLogger: copied byte for byte. Sessions from the removed PCAPdroid bridge in its
        // FILE mode have their capture in PCAPdroid's Download folder (session.captureFile names it); not exported.
        val captureBytes = captureFiles.sumOf { it.second.length() }.coerceAtLeast(1)
        var copied = 0L
        captureFiles.forEach { (name, file) ->
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    zip.write(buf, 0, n)
                    copied += n
                    onProgress(ExportProgress(name, (pass + copied.toDouble() / captureBytes) / parts))
                }
            }
            zip.closeEntry()
        }
        val counts = SessionJson.Counts(
            measurements, cells, events.size.toLong(), warnings.size.toLong(), trackPoints, throughput.size.toLong(),
            waypoints = indoor.waypoints, indoorTicks = indoor.ticks,
        )
        val files = FILES + indoor.files + captureFiles.map { it.first }
        entry(zip, SESSION) { w -> w.write(JSON.encodeToString(SessionJson.serializer(), sessionJson(session, time, nowMs, counts, files))) }
        zip.finish()
        return ExportResult(fileName(session, zone), counts)
    }

    private class IndoorExport(val waypoints: Long = 0, val ticks: Long = 0, val files: List<String> = emptyList())

    /**
     * Floor plan positions (decision 006): every tap as stored, the ticks placed between taps (derived now by
     * [PlanInterpolator], D2), and the plans with their images. Nothing for a session without taps.
     */
    private suspend fun exportIndoor(zip: ZipOutputStream, sessionId: Long, columns: Columns, includeImages: Boolean): IndoorExport {
        val stored = dao.waypoints(sessionId)
        if (stored.isEmpty()) return IndoorExport()
        val planById = dao.floorPlans(sessionId).associateBy { it.id }
        entry(zip, WAYPOINTS) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.waypoints.map { it.name })
            csv.writeAll(columns.waypoints, stored.map { WaypointRow(it, planById[it.planId]) })
        }
        val active = stored.filterNot { it.removed }.map { Waypoint(it.planId, it.wallTimeMs, it.elapsedRealtimeNs, it.x, it.y, removed = false) }
        var placed = 0L
        entry(zip, INDOOR) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.indoor.map { it.name })
            pages(sessionId) { page ->
                val rows = page.zip(PlanInterpolator.positions(page.map { it.elapsedRealtimeNs }, active))
                    .mapNotNull { (m, p) -> p?.let { IndoorRow(m, it, planById[it.planId]) } }
                csv.writeAll(columns.indoor, rows)
                placed += rows.size
            }
        }
        val images = if (includeImages) {
            planById.values.mapNotNull { p -> plans?.image(p.imageFile)?.takeIf { it.isFile }?.let { "$PLAN_DIR${p.uuid}.png" to it } }
        } else {
            emptyList()
        }
        images.forEach { (name, file) ->
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip, BUFFER_BYTES) }
            zip.closeEntry()
        }
        val taps = stored.filterNot { it.removed }.groupingBy { it.planId }.eachCount()
        val plansJson = planById.values.map { p ->
            PlanJson(
                uuid = p.uuid, name = p.name, floor = p.floor, widthPx = p.widthPx, heightPx = p.heightPx,
                metresPerPx = p.metresPerPx, taps = taps[p.id] ?: 0,
                image = "$PLAN_DIR${p.uuid}.png".takeIf { name -> images.any { it.first == name } },
            )
        }
        entry(zip, PLANS) { w -> w.write(JSON.encodeToString(ListSerializer(PlanJson.serializer()), plansJson)) }
        return IndoorExport(stored.size.toLong(), placed, listOf(WAYPOINTS, INDOOR, PLANS) + images.map { it.first })
    }

    private suspend fun pages(sessionId: Long, block: suspend (List<MeasurementEntity>) -> Unit) {
        var after = -1L
        while (true) {
            val page = dao.measurements(sessionId, after, PAGE_TICKS)
            if (page.isEmpty()) return
            block(page)
            after = page.last().seq
        }
    }

    private suspend fun entry(zip: ZipOutputStream, name: String, write: suspend (Writer) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        // Not closed: closing would close the ZIP stream. Flushed so the entry is complete before closeEntry().
        val writer = OutputStreamWriter(zip, Charsets.UTF_8).buffered(BUFFER_BYTES)
        write(writer)
        writer.flush()
        zip.closeEntry()
    }

    private fun sessionJson(s: SessionEntity, time: IsoTime, nowMs: Long, counts: SessionJson.Counts, files: List<String>) = SessionJson(
        uuid = s.uuid,
        label = s.label,
        scenario = s.scenario,
        notes = s.notes,
        startedAt = time.format(s.startedAt),
        startedAtMs = s.startedAt,
        startedElapsedRealtimeNs = s.startedElapsedNs,
        endedAt = time.format(s.endedAt),
        endedAtMs = s.endedAt,
        endedElapsedRealtimeNs = s.endedElapsedNs,
        endReason = s.endReason,
        durationMs = s.endedAt?.let { it - s.startedAt },
        intervalMs = s.intervalMs,
        timeZone = time.zone.id,
        utcOffset = time.offset(s.startedAt),
        exportedAt = time.format(nowMs),
        appVersion = s.appVersion,
        schemaVersion = s.schemaVersion,
        device = SessionJson.Device(s.deviceManufacturer, s.deviceModel, s.androidVersion, s.basebandVersion),
        network = SessionJson.Network(s.subscriptionId, s.simSlot, s.operatorName, s.mcc, s.mnc),
        counts = counts,
        captureFile = s.captureFile,
        files = files,
    )

    companion object {
        const val SESSION = "session.json"
        const val MEASUREMENTS = "measurements.csv"
        const val CELLS = "cells.csv"
        const val EVENTS = "events.csv"
        const val WARNINGS = "warnings.csv"
        const val THROUGHPUT = "throughput.csv"
        const val CAPTURE = "capture.pcap"
        const val CAPTURE_SYNC = "capture_sync.csv"
        const val CONNECTIONS = "connections.csv"
        const val FLAT = "flat.csv"
        const val TRACK = "track.geojson"
        const val WAYPOINTS = "waypoints.csv"
        const val INDOOR = "indoor.csv"
        const val PLANS = "plans.json"
        const val PLAN_DIR = "plans/"
        val FILES = listOf(SESSION, MEASUREMENTS, CELLS, EVENTS, WARNINGS, THROUGHPUT, FLAT, TRACK)

        private const val PAGE_TICKS = 500

        /** measurements, cells, flat, track: each reads every tick once. */
        private const val TICK_PASSES = 4
        private const val BUFFER_BYTES = 64 * 1024
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }

        /** netlogger_<model>_<start yyyyMMdd-HHmmss>_<label or scenario>_<uuid prefix>.zip, ASCII only. */
        fun fileName(s: SessionEntity, zone: ZoneId): String {
            val start = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(s.startedAt))
            val parts = listOf("netlogger", s.deviceModel, start, s.label?.takeIf { it.isNotBlank() } ?: s.scenario?.lowercase(), s.uuid.take(8))
            return parts.filterNotNull().joinToString("_") { safe(it) } + ".zip"
        }

        private fun safe(part: String): String =
            part.trim().replace(Regex("[^A-Za-z0-9.-]+"), "-").trim('-').take(40).ifEmpty { "x" }
    }
}
