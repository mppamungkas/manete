package id.my.pamslab.netlogger.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemTest {

    @Test
    fun `process state summary round trip within 128 bytes`() {
        val s = ProcessStateSummary(sessionId = 31, capture = true, screen = "MapRoute")
        assertEquals("s=31;c=1;v=MapRoute", s.encode().toString(Charsets.US_ASCII))
        assertEquals(s, ProcessStateSummary.decode(s.encode()))
        assertEquals(ProcessStateSummary(), ProcessStateSummary.decode(ProcessStateSummary().encode()))
        val long = ProcessStateSummary(sessionId = Long.MAX_VALUE, screen = "x".repeat(500) + "?;=")
        assertTrue(long.encode().size <= ProcessStateSummary.MAX_BYTES)
        assertEquals("x".repeat(40), ProcessStateSummary.decode(long.encode())?.screen)
    }

    @Test
    fun `unreadable or missing summaries are null`() {
        assertNull(ProcessStateSummary.decode(null))
        assertNull(ProcessStateSummary.decode(ByteArray(0)))
        assertNull(ProcessStateSummary.decode("garbage".toByteArray()))
    }

    @Test
    fun `crashes and ANRs always, kills only while recording, never a force stop or update`() {
        val recording = ProcessStateSummary(sessionId = 5)
        assertEquals(ProblemKind.CRASH, ExitReason.classify(ExitReason.CRASH, null))
        assertEquals(ProblemKind.CRASH_NATIVE, ExitReason.classify(ExitReason.CRASH_NATIVE, null))
        assertEquals(ProblemKind.ANR, ExitReason.classify(ExitReason.ANR, ProcessStateSummary()))
        assertEquals(ProblemKind.KILLED_WHILE_RECORDING, ExitReason.classify(ExitReason.LOW_MEMORY, recording))
        assertNull(ExitReason.classify(ExitReason.LOW_MEMORY, ProcessStateSummary()))
        assertNull(ExitReason.classify(ExitReason.SIGNALED, null))
        assertNull(ExitReason.classify(ExitReason.USER_REQUESTED, recording))
        assertNull(ExitReason.classify(ExitReason.PACKAGE_UPDATED, recording))
        assertNull(ExitReason.classify(ExitReason.EXIT_SELF, recording))
    }

    @Test
    fun `own log drops separators and coordinate pairs`() {
        val lines = listOf(
            "--------- beginning of main",
            "10-16 05:51:08.379 26065 26166 W NetLogger: tick 12 not stored",
            "10-16 05:51:08.380 26065 26166 I NetLogger: fix -6.200000, 106.800000",
            "10-16 05:51:08.381 26065 26166 I NetLogger: at -6.2000/106.8000",
            "10-16 08:57:39.988 D/Mbgl-HttpRequest( 8739): [HTTP] This request was cancelled (https://tile.openstreetmap.org/17/102092/65867.png).",
        )
        assertEquals(listOf(lines[1]), OwnLog.filter(lines))
    }
}
