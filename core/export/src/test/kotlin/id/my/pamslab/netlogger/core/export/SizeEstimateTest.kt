package id.my.pamslab.netlogger.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeEstimateTest {
    @Test
    fun `estimates from row counts, capture files added as they are`() {
        val e = SizeEstimate.of(ticks = 1_000, cells = 5_000, warnings = 10, captureBytes = 2_000_000)
        assertEquals(1_000 * 185L + 5_000 * 87L + 10 * 110L, e.databaseBytes)
        assertEquals(e.databaseBytes + 2_000_000, e.storageBytes)
        assertEquals(3_000 + 1_000 * 135L + 5_000 * 9L + 2_000_000, e.exportBytes)
    }

    @Test
    fun `measured sessions are within 20 percent`() {
        // Actual deflated sizes of the CSV + GeoJSON entries (2026-10-16): moto session 16, A56 session 7.
        listOf(Triple(2_146L, 10_472L, 376_517L), Triple(1_389L, 5_282L, 239_458L)).forEach { (ticks, cells, actual) ->
            val est = SizeEstimate.of(ticks, cells, 0).exportBytes
            assertTrue("$est vs $actual", est in (actual * 0.8).toLong()..(actual * 1.2).toLong())
        }
    }

    @Test
    fun `formats with decimal units`() {
        assertEquals("820 B", SizeEstimate.format(820))
        assertEquals("12 kB", SizeEstimate.format(12_345))
        assertEquals("4.6 MB", SizeEstimate.format(4_567_890))
        assertEquals("101 MB", SizeEstimate.format(101_300_000))
        assertEquals("1.2 GB", SizeEstimate.format(1_234_000_000))
    }
}
