package id.my.pamslab.netlogger.core.telephony.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NrBandTableTest {

    @Test
    fun `global raster frequencies`() {
        assertEquals(2_659_350L, NrBandTable.frequencyKhz(531_870))
        assertEquals(3_000_000L, NrBandTable.frequencyKhz(600_000))
        assertEquals(3_500_010L, NrBandTable.frequencyKhz(633_334))
        assertEquals(24_250_080L, NrBandTable.frequencyKhz(2_016_667))
        assertNull(NrBandTable.frequencyKhz(-1))
        assertNull(NrBandTable.frequencyKhz(3_279_166))
    }

    @Test
    fun `moto g45 Tri NR-ARFCN 531870 is n41 in Indonesia, ambiguous elsewhere`() {
        assertEquals(BandMapping(41, listOf(7, 41, 90), countryPreferred = true), NrBandTable.map(531_870, "510"))
        assertEquals(7, NrBandTable.map(531_870, "262")?.band)
        assertEquals(7, NrBandTable.map(531_870, null)?.band)
    }

    @Test
    fun `n41 edges`() {
        assertEquals(listOf(41, 90), NrBandTable.candidates(499_200)) // 2496 MHz
        assertEquals(emptyList<Int>(), NrBandTable.candidates(499_199)) // 2495.995 MHz, above n53
        assertEquals(listOf(7, 41, 90), NrBandTable.candidates(538_000)) // 2690 MHz
        assertEquals(emptyList<Int>(), NrBandTable.candidates(538_001))
    }

    @Test
    fun `common FR1 and FR2 bands`() {
        assertEquals(listOf(77, 78), NrBandTable.candidates(636_666)) // 3549.99 MHz
        assertEquals(78, NrBandTable.map(636_666, "510")?.band) // narrowest of n77/n78
        assertEquals(78, NrBandTable.map(636_666, null)?.band)
        assertEquals(41, NrBandTable.map(520_000, "510")?.band) // 2600 MHz: n38/n41/n90 → n41 in Indonesia
        assertEquals(38, NrBandTable.map(520_000, null)?.band) // narrowest elsewhere
        assertEquals(listOf(40), NrBandTable.candidates(468_000)) // 2340 MHz (2350 is also the n30 edge)
        assertEquals(listOf(3), NrBandTable.candidates(368_500)) // 1842.5 MHz
        assertEquals(listOf(1, 65, 66), NrBandTable.candidates(428_000)) // 2140 MHz
        assertEquals(1, NrBandTable.map(428_000, "510")?.band)
        assertEquals(listOf(8), NrBandTable.candidates(188_500)) // 942.5 MHz
        assertEquals(listOf(258), NrBandTable.candidates(2_029_167)) // ~25 GHz
    }
}
