package id.my.pamslab.netlogger.core.data

import id.my.pamslab.netlogger.core.model.radio.ParseWarning
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.radio.WarningCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TickMapperTest {

    @Test
    fun `measurement keeps both timestamps, NSA inputs and nulls`() {
        val m = TickMapper.measurement(7, TestData.tick(3, withFix = false))
        assertEquals(7L, m.sessionId)
        assertEquals(3L, m.seq)
        assertEquals(TestData.START_MS + 3_000, m.wallTimeMs)
        assertEquals(TestData.START_NS + 3_000_000_000, m.elapsedRealtimeNs)
        assertEquals("NR_NSA", m.networkType)
        assertTrue(m.nsaLteServing && m.nsaNrCellInfo && m.nsaNrSignal)
        assertEquals(3, m.overrideNetworkType)
        assertEquals("[20000,10000]", m.cellBandwidthsJson)
        assertNull(m.lat)
        assertNull(m.locationAgeMs)
        assertEquals("WAITING", m.locationStatus)
        assertEquals(2, m.parseErrors)
    }

    @Test
    fun `cell maps every field and keeps nulls`() {
        val c = TickMapper.cell(TestData.nr)
        assertEquals("NR", c.rat)
        assertEquals("SECONDARY_SERVING", c.connectionStatus)
        assertEquals(41, c.band)
        assertEquals("MAPPED", c.bandSource)
        assertNull(c.ciOrNci)
        assertNull(c.tac)
        assertNull(c.rsrpDbm)
        assertEquals("CELL_INFO", c.source)
        assertEquals("SIGNAL_STRENGTH", TickMapper.cell(TestData.nrSignal).source)
    }

    @Test
    fun `warnings are deduplicated per session key and counted`() {
        val dedup = WarningDeduplicator(sessionId = 1)
        val first = dedup.plan(listOf(TestData.bandWarning, TestData.tacWarning, TestData.tacWarning.copy(rawValue = "19351")), 1_000, 10)
        assertEquals(2, first.newRows.size)
        val tac = first.newRows.single { it.code == "NR_TAC_FROM_LTE" }
        assertEquals(2L, tac.occurrences)
        assertEquals("19350", tac.rawValue)
        assertEquals("19351", tac.lastRawValue)
        assertTrue(first.bumps.isEmpty())
        dedup.committed(first, listOf(100, 101))

        val other = ParseWarning(WarningCode.NR_TAC_FROM_LTE, Rat.NR, 440, "tac", "0")
        val second = dedup.plan(listOf(TestData.bandWarning, other), 2_000, 20)
        assertEquals(listOf(100L), second.bumps.map { it.id })
        assertEquals(1L, second.bumps.single().count)
        assertEquals(2_000L, second.bumps.single().lastSeenMs)
        assertEquals("different pci is a different key", 1, second.newRows.size)
    }

    @Test
    fun `uncommitted plans do not register keys`() {
        val dedup = WarningDeduplicator(1)
        dedup.plan(listOf(TestData.bandWarning), 1_000, 10)
        assertEquals(1, dedup.plan(listOf(TestData.bandWarning), 2_000, 20).newRows.size)
    }
}
