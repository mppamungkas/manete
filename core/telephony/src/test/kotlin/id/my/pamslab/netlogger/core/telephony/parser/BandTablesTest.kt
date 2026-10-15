package id.my.pamslab.netlogger.core.telephony.parser

import id.my.pamslab.netlogger.core.model.radio.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** LTE, WCDMA and GSM tables, and the bands Indonesian operators use (milestone 7). */
class BandTablesTest {

    private fun lte(earfcn: Int) = LteBandTable.map(earfcn)?.band

    @Test
    fun `LTE EARFCNs seen on our phones match getBands() - Tri (510-89), 2026-10-13 and -14`() {
        // getBands() on moto g45 and A56 for the registered cell (excluding the moto's handover glitch).
        mapOf(0 to 1, 75 to 1, 200 to 1, 1475 to 3, 1625 to 3, 3652 to 8).forEach { (earfcn, band) ->
            assertEquals("EARFCN $earfcn", band, lte(earfcn))
        }
    }

    @Test
    fun `LTE bands used in Indonesia - 1, 3, 5, 8, 40, and 28 (700 MHz APT)`() {
        assertEquals(1, lte(300)) // 2140 MHz
        assertEquals(3, lte(1850)) // 1870 MHz
        assertEquals(5, lte(2525)) // 881.5 MHz
        assertEquals(8, lte(3500)) // 930 MHz
        assertEquals(40, lte(38_948)) // 2329.8 MHz
        assertEquals(28, lte(9_460)) // 783 MHz
    }

    @Test
    fun `LTE band edges and gaps`() {
        assertEquals(1, lte(599)); assertEquals(2, lte(600))
        assertEquals(3, lte(1949)); assertEquals(4, lte(1950))
        assertEquals(40, lte(39_649)); assertEquals(41, lte(39_650)); assertEquals(41, lte(41_589))
        assertEquals(66, lte(66_436)); assertEquals(65, lte(66_435))
        assertNull("gap after band 32", lte(10_360))
        assertNull("gap before band 33", lte(35_999))
        assertNull(lte(70_546))
        assertEquals(listOf(3), LteBandTable.map(1625)?.candidates)
    }

    @Test
    fun `WCDMA bands - I and VIII used in Indonesia`() {
        assertEquals(1, WcdmaBandTable.map(10_563)?.band) // moto g45 neighbor, 2026-10-14
        assertEquals(1, WcdmaBandTable.map(10_788)?.band)
        assertEquals(8, WcdmaBandTable.map(3_013)?.band)
        assertEquals(5, WcdmaBandTable.map(4_400)?.band)
        assertEquals(2, WcdmaBandTable.map(9_800)?.band)
        assertNull(WcdmaBandTable.map(10_839))
        assertNull(WcdmaBandTable.map(412)) // band II additional channel: not mapped
    }

    @Test
    fun `GSM bands in MHz - 900 and 1800 in Indonesia, 1900 in the Americas`() {
        assertEquals(900, GsmBandTable.map(50, "510")?.band)
        assertEquals(900, GsmBandTable.map(1_000, "510")?.band) // E-GSM
        assertEquals(850, GsmBandTable.map(190, "310")?.band)
        assertEquals(BandMapping(1800, listOf(1800, 1900)), GsmBandTable.map(600, "510"))
        assertEquals(1800, GsmBandTable.map(600, null)?.band)
        assertEquals(1900, GsmBandTable.map(600, "310")?.band)
        assertEquals(1800, GsmBandTable.map(850, "310")?.band) // above PCS: DCS only
        assertNull(GsmBandTable.map(300, "510"))
    }

    @Test
    fun `NR bands used in Indonesia`() {
        fun nr(arfcn: Int) = NrBandTable.map(arfcn, "510")?.band
        assertEquals(1, nr(428_000)) // 2140 MHz
        assertEquals(3, nr(368_500)) // 1842.5 MHz
        assertEquals(5, nr(176_300)) // 881.5 MHz
        assertEquals(8, nr(186_000)) // 930 MHz
        assertEquals(40, nr(468_000)) // 2340 MHz
        assertEquals(41, nr(531_870)) // 2659.35 MHz, Tri
        assertEquals(28, nr(152_600)) // 763 MHz: n28, not n14
        assertEquals(14, NrBandTable.map(152_600, "310")?.band)
    }

    @Test
    fun `the standard mapper covers every RAT`() {
        assertEquals(3, BandMapper.Standard.map(Rat.LTE, 1625, "510")?.band)
        assertEquals(41, BandMapper.Standard.map(Rat.NR, 531_870, "510")?.band)
        assertEquals(1, BandMapper.Standard.map(Rat.WCDMA, 10_563, "510")?.band)
        assertEquals(1800, BandMapper.Standard.map(Rat.GSM, 700, "510")?.band)
    }
}
