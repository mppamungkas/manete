package id.my.pamslab.netlogger.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.TimeZone

class ReportBuilderTest {

    private val ctx = ReportContext("0.1.0", "511cf00", "motorola", "moto g45 5G", "15", 35, TimeZone.getTimeZone("GMT+07:00"))

    // 2026-10-16 05:51:08 +07:00
    private val t0 = 1_792_104_668_000L

    @Test
    fun `tombstone summary - signal, cause, crashing thread, library names without the install path`() {
        val s = TombstoneSummary.parse(tombstone())!!
        assertEquals("SIGSEGV (SEGV_MAPERR)", s.signal)
        assertEquals(0L, s.faultAddress) // proto3 omits the zero address
        assertEquals("null pointer dereference", s.cause)
        assertEquals("RenderThread 20", s.threadName)
        assertEquals(
            listOf("#00 libmaplibre.so mln::android::MapRenderer::render(_JNIEnv&)+144", "#01 libart.so pc 0x1f00"),
            s.frames,
        )
        assertEquals("SIGSEGV (SEGV_MAPERR) in libmaplibre.so mln::android::MapRenderer::render(_JNIEnv&)", s.headline())
    }

    @Test
    fun `unreadable tombstones are null, not a crash`() {
        assertNull(TombstoneSummary.parse(byteArrayOf(0x0A, 0x7F)))
        assertNull(TombstoneSummary.parse(ByteArray(0)))
        assertNull(TombstoneSummary.parse("not a protobuf at all".toByteArray()))
    }

    @Test
    fun `native crash report`() {
        val report = ReportBuilder.build(
            ReportInput(problem(ProblemKind.CRASH_NATIVE, ExitReason.CRASH_NATIVE, state = ProcessStateSummary(screen = "MapRoute")), trace = tombstone(),
                logLines = listOf("10-16 05:51:08.379 26065 26166 F libc    : Fatal signal 11", "x tile.openstreetmap.org/17/102092/65867.png")),
            ctx,
        )
        assertTrue(report, report.contains("Time       2026-10-16 05:51:08 +07:00"))
        assertTrue(report.contains("Process    main · foreground"))
        assertTrue(report.contains("What       Crash (native): SIGSEGV (SEGV_MAPERR) in libmaplibre.so mln::android::MapRenderer::render(_JNIEnv&)"))
        assertTrue(report.contains("Running    Map screen; no recording; no capture"))
        assertTrue(report.contains("  SIGSEGV (SEGV_MAPERR), fault address 0x0"))
        assertTrue(report.contains("  #00 libmaplibre.so mln::android::MapRenderer::render(_JNIEnv&)+144"))
        assertTrue(report.contains("Log (NetLogger's own lines before the end, 1)"))
        assertFalse(report.contains("/data/app"))
        assertFalse(report.contains("tile.openstreetmap.org"))
    }

    @Test
    fun `java crash report uses the crash time and the innermost cause, and notes a late process end`() {
        val java = """
            thread: main
            timeMs: $t0

            java.lang.RuntimeException: Unable to start receiver
            	at android.app.ActivityThread.handleReceiver(ActivityThread.java:4915)
            Caused by: java.lang.IllegalStateException: NetLogger crash test (debug build)
            	at id.my.pamslab.netlogger.CrashTestReceiver.onReceive(CrashTestReceiver.kt:23)

            log:
            10-16 05:51:08.000 1 1 W NetLogger: crash test: java
        """.trimIndent()
        val p = problem(ProblemKind.CRASH, ExitReason.CRASH, timeMs = t0 + 75_000, state = ProcessStateSummary(sessionId = 22, screen = "LiveRoute"), importance = 125)
        val report = ReportBuilder.build(ReportInput(p, javaText = java, logLines = listOf("ignored: java.txt has its own log")), ctx)

        assertTrue(report, report.contains("Time       2026-10-16 05:51:08 +07:00"))
        assertTrue(report.contains("Ended      2026-10-16 05:52:23 +07:00 (Android kept the process until then)"))
        assertTrue(report.contains("What       Crash: java.lang.IllegalStateException: NetLogger crash test (debug build)"))
        assertTrue(report.contains("Running    Live screen; recording session 22; no capture"))
        assertTrue(report.contains("Process    main · foreground service"))
        assertTrue(report.contains("  at id.my.pamslab.netlogger.CrashTestReceiver.onReceive(CrashTestReceiver.kt:23)".replace("  at", "  \tat")))
        assertTrue(report.contains("W NetLogger: crash test: java"))
        assertFalse(report.contains("ignored"))
        assertEquals("Crash: java.lang.IllegalStateException: NetLogger crash test (debug build)", ReportBuilder.title(p, java))
    }

    @Test
    fun `ANR shows the main thread, kills during a recording and user reports have no trace`() {
        val dump = "----- pid 1 -----\n\"Signal Catcher\" daemon\n  | state=R\n\n\"main\" prio=5 tid=1 Sleeping\n  at java.lang.Thread.sleep(Native method)\n  at id.my.pamslab.netlogger.CrashTestReceiver.onReceive(CrashTestReceiver.kt:27)\n\n\"other\" prio=5\n"
        val anr = ReportBuilder.build(ReportInput(problem(ProblemKind.ANR, ExitReason.ANR, process = "id.my.pamslab.netlogger:vpn", importance = 400), trace = dump.toByteArray()), ctx)
        assertTrue(anr, anr.contains("Process    capture (:vpn) · background"))
        assertTrue(anr.contains("  \"main\" prio=5 tid=1 Sleeping"))
        assertTrue(anr.contains("CrashTestReceiver.kt:27"))
        assertFalse(anr.contains("Signal Catcher"))
        assertFalse(anr.contains("\"other\""))

        assertEquals(
            "Input dispatching timed out",
            ReportBuilder.anrReason("user request after error: Input dispatching timed out (a9dc28a id.my.pamslab.netlogger/id.my.pamslab.netlogger.MainActivity is not responding. Waited 10000ms for FocusEvent(hasFocus=false))."),
        )
        assertEquals("Broadcast of Intent", ReportBuilder.anrReason("Broadcast of Intent (x)"))

        val killed = ReportBuilder.build(ReportInput(problem(ProblemKind.KILLED_WHILE_RECORDING, ExitReason.LOW_MEMORY, state = ProcessStateSummary(sessionId = 3, capture = true))), ctx)
        assertTrue(killed, killed.contains("What       Ended by Android during a recording (LOW_MEMORY)"))
        assertTrue(killed.contains("Running    recording session 3; capture on"))
        assertTrue(killed.contains("Log: none readable"))
        assertFalse(killed.contains("Trace"))

        assertEquals("Crash (no stack trace kept)", ReportBuilder.title(problem(ProblemKind.CRASH, ExitReason.CRASH)))

        val user = ReportBuilder.build(ReportInput(null, logLines = listOf("a line"), userNote = "  Map stays grey on the A56.  ", reportedAtMs = t0), ctx)
        assertTrue(user, user.startsWith("NetLogger problem report (reported by the user)"))
        assertTrue(user.contains("Your note\nMap stays grey on the A56.\n"))
        assertTrue(user.contains("Time       2026-10-16 05:51:08 +07:00"))
        assertFalse(user.contains("What "))
    }

    private fun problem(
        kind: ProblemKind,
        reason: Int,
        timeMs: Long = t0,
        state: ProcessStateSummary? = null,
        process: String = "id.my.pamslab.netlogger",
        importance: Int = 100,
    ) = Problem(
        id = "x", kind = kind, timeMs = timeMs, pid = 1, processName = process, reason = reason,
        reasonName = ExitReason.name(reason), status = 11, importance = importance, description = null, pssKb = 0, rssKb = 0,
        state = state, hasTrace = false, hasJavaStack = false, hasLog = false,
    )

    /** A tombstone shaped like the moto g45's (2026-10-16): crashing tid 7 among two threads. */
    private fun tombstone(): ByteArray {
        val frame0 = msg { str(4, "mln::android::MapRenderer::render(_JNIEnv&)"); int(5, 144); str(6, "/data/app/~~a==/id.my.pamslab.netlogger-b==/base.apk!libmaplibre.so"); int(1, 0x5e7c80) }
        val frame1 = msg { int(1, 0x1f00); str(6, "/apex/com.android.art/lib64/libart.so") }
        val crashing = msg { int(1, 7); str(2, "RenderThread 20"); bytes(4, frame0); bytes(4, frame1); bytes(5, msg { str(1, "memory dump: secret") }) }
        val other = msg { int(1, 1); str(2, "main"); bytes(4, msg { str(4, "main_frame"); str(6, "libc.so") }) }
        return msg {
            int(5, 26065); int(6, 7)
            bytes(10, msg { int(1, 11); str(2, "SIGSEGV"); int(3, 1); str(4, "SEGV_MAPERR"); int(8, 1) })
            bytes(15, msg { str(1, "null pointer dereference") })
            bytes(16, msg { int(1, 1); bytes(2, other) })
            bytes(16, msg { int(1, 7); bytes(2, crashing) })
            bytes(18, msg { str(1, "main"); str(2, "log buffer: secret") })
        }
    }

    private class Enc {
        val out = ByteArrayOutputStream()
        private fun varint(v: Long) {
            var x = v
            while (x and 0x7F.inv().toLong() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        fun int(n: Int, v: Long) { varint((n shl 3).toLong()); varint(v) }
        fun int(n: Int, v: Int) = int(n, v.toLong())
        fun bytes(n: Int, b: ByteArray) { varint(((n shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(n: Int, s: String) = bytes(n, s.toByteArray())
    }

    private fun msg(block: Enc.() -> Unit): ByteArray = Enc().apply(block).out.toByteArray()
}
