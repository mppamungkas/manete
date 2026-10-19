package id.my.pamslab.netlogger.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.TimeZone

class CaughtErrorsTest {

    private val zone = TimeZone.getTimeZone("GMT+07:00")

    // 2026-10-19 10:21:00 +07:00
    private val t0 = 1_792_380_060_000L

    private fun failing(): Throwable = try {
        throwFromOwnCode()
    } catch (e: Exception) {
        e
    }

    private fun throwFromOwnCode(): Nothing = throw IOException("connect to 203.0.113.7:8080 failed", SecurityException("uid 10123"))

    @Test
    fun `the same error is counted, not stored again, and never keeps the message`() {
        val log = CaughtErrorLog("main")
        val e = failing()
        assertTrue("new kind", log.record("speed server unreachable", e, t0))
        assertFalse("same kind", log.record("speed server unreachable", e, t0 + 60_000))
        assertTrue("other place", log.record("location request failed", null, t0 + 120_000))

        val all = log.snapshot(t0 + 120_000)
        assertEquals(2, all.size)
        val server = all.single { it.where == "speed server unreachable" }
        assertEquals(2L, server.count)
        assertEquals(t0, server.firstMs)
        assertEquals(t0 + 60_000, server.lastMs)
        assertEquals("IOException ← SecurityException", server.exception)
        assertTrue(server.frame!!.startsWith("CaughtErrorsTest.throwFromOwnCode:"))
        assertFalse("no IP or uid from the message", all.toString().contains("203.0.113.7") || all.toString().contains("10123"))
    }

    @Test
    fun `entries older than 7 days are dropped, restored ones keep their counts`() {
        val old = CaughtError("export failed", "IOException", null, "main", 3, t0 - 9 * 86_400_000L, t0 - 8 * 86_400_000L)
        val recent = CaughtError("tick not stored", "SQLiteFullException", "LoggingEngine.run:180", "main", 5, t0 - 3_600_000, t0 - 60_000)
        val log = CaughtErrorLog("main")
        log.restore(listOf(old, recent), t0)
        assertEquals(listOf(recent), log.snapshot(t0))
        log.record("tick not stored", null, t0)
        assertEquals("a different frame is a different kind", 2, log.snapshot(t0).size)
    }

    @Test
    fun `at most 50 kinds, the oldest goes`() {
        val log = CaughtErrorLog("vpn")
        repeat(CaughtErrorLog.MAX_ENTRIES + 1) { log.record("error $it", null, t0 + it) }
        val kept = log.snapshot(t0 + 100)
        assertEquals(CaughtErrorLog.MAX_ENTRIES, kept.size)
        assertFalse(kept.any { it.where == "error 0" })
    }

    @Test
    fun `summary lines - count, kind, frame, time span and process`() {
        val lines = CaughtErrorLog.summary(
            listOf(
                CaughtError("extract signalStrength.timestamp failed", "NoSuchMethodError", "Extract.get:44", "main", 12, t0, t0 + 19 * 60_000),
                CaughtError("getConnectionOwnerUid failed", "SecurityException", null, "vpn", 1, t0 - 86_400_000L, t0 - 86_400_000L),
            ),
            zone,
        )
        assertEquals(
            listOf(
                "12× extract signalStrength.timestamp failed · NoSuchMethodError · Extract.get:44 · 10-19 10:21–10:40 · main",
                "1× getConnectionOwnerUid failed · SecurityException · 10-18 10:21 · vpn",
            ),
            lines,
        )
    }

    @Test
    fun `user report lists caught errors before the log`() {
        val ctx = ReportContext("0.3.1", "abc1234", "samsung", "SM-A566B", "16", 36, zone)
        val text = ReportBuilder.build(
            ReportInput(
                problem = null, userNote = "speed test stops", reportedAtMs = t0,
                caught = listOf(CaughtError("speed test: upload failed", "IOException", null, "main", 2, t0, t0 + 60_000)),
            ),
            ctx,
        )
        val section = text.indexOf("Caught errors (last 7 days, NetLogger kept running; 1 kinds)")
        assertTrue(text, section > text.indexOf("Your note"))
        assertTrue(text, section < text.indexOf("Log: none readable"))
        assertTrue(text.contains("  2× speed test: upload failed · IOException · 10-19 10:21–10:22 · main"))
        assertFalse("none: no section", ReportBuilder.build(ReportInput(problem = null, userNote = "x"), ctx).contains("Caught errors"))
    }
}
