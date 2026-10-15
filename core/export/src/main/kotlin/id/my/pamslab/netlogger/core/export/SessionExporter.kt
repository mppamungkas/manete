package id.my.pamslab.netlogger.core.export

import id.my.pamslab.netlogger.core.data.CaptureFileStore
import id.my.pamslab.netlogger.core.data.db.ExportDao
import id.my.pamslab.netlogger.core.data.db.MeasurementEntity
import id.my.pamslab.netlogger.core.data.db.SessionEntity
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

/**
 * Writes one session as a ZIP bundle: session.json, measurements.csv, cells.csv, events.csv, warnings.csv, throughput.csv, flat.csv and
 * track.geojson. Ticks are read in pages of [PAGE_TICKS], so memory stays flat for long sessions. Timestamps are
 * formatted in [zone] (the device zone at export; sessions do not store one) next to the raw epoch ms and
 * elapsedRealtime ns columns.
 */
class SessionExporter @Inject constructor(
    private val dao: ExportDao,
    /** TCP-mode traffic captures (Phase 3a); none in tests. */
    private val captures: CaptureFileStore? = null,
) {

    suspend fun fileName(sessionId: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val s = requireNotNull(dao.session(sessionId)) { "session $sessionId not found" }
        return fileName(s, zone)
    }

    /** Throws IllegalArgumentException for a missing or still-recording session; [out] is not closed. */
    suspend fun export(sessionId: Long, out: OutputStream, zone: ZoneId = ZoneId.systemDefault(), nowMs: Long = System.currentTimeMillis()): ExportResult {
        val session = requireNotNull(dao.session(sessionId)) { "session $sessionId not found" }
        require(session.endedAt != null) { "session is still recording" }
        val time = IsoTime(zone)
        val columns = Columns(time)
        val zip = ZipOutputStream(out)

        var measurements = 0L
        var cells = 0L
        entry(zip, MEASUREMENTS) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.measurements.map { it.name })
            pages(sessionId) { page -> csv.writeAll(columns.measurements, page); measurements += page.size }
        }
        entry(zip, CELLS) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.cells.map { it.name })
            pages(sessionId) { page ->
                val rows = dao.cells(sessionId, page.first().seq, page.last().seq)
                csv.writeAll(columns.cells, rows)
                cells += rows.size
            }
        }
        entry(zip, FLAT) { w ->
            val csv = CsvWriter(w)
            csv.header(columns.flat.map { it.name })
            pages(sessionId) { page ->
                val bySeq = dao.cells(sessionId, page.first().seq, page.last().seq).groupBy({ it.seq }, { it.cell })
                csv.writeAll(columns.flat, page.map { FlatRow.of(it, bySeq[it.seq].orEmpty()) })
            }
        }
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
            pages(sessionId) { page ->
                page.forEach { m ->
                    if (m.lat != null && m.lon != null) trackPoints++
                    geo.point(m.lat, m.lon, m.wallTimeMs)
                }
            }
            geo.finish(events)
        }
        // Traffic capture written by NetLogger: copied byte for byte. Sessions from the removed PCAPdroid bridge in its
        // FILE mode have their capture in PCAPdroid's Download folder (session.captureFile names it); not exported.
        val captureFiles = captures?.let { store ->
            listOfNotNull(
                store.pcap(session.uuid).takeIf { it.isFile }?.let { CAPTURE to it },
                store.sync(session.uuid).takeIf { it.isFile }?.let { CAPTURE_SYNC to it },
                store.connections(session.uuid).takeIf { it.isFile }?.let { CONNECTIONS to it },
            )
        }.orEmpty()
        captureFiles.forEach { (name, file) ->
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip, BUFFER_BYTES) }
            zip.closeEntry()
        }
        val counts = SessionJson.Counts(measurements, cells, events.size.toLong(), warnings.size.toLong(), trackPoints, throughput.size.toLong())
        val files = FILES + captureFiles.map { it.first }
        entry(zip, SESSION) { w -> w.write(JSON.encodeToString(SessionJson.serializer(), sessionJson(session, time, nowMs, counts, files))) }
        zip.finish()
        return ExportResult(fileName(session, zone), counts)
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
        val FILES = listOf(SESSION, MEASUREMENTS, CELLS, EVENTS, WARNINGS, THROUGHPUT, FLAT, TRACK)

        private const val PAGE_TICKS = 500
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
