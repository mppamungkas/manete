package id.my.pamslab.netlogger.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class FlatRowTest {

    private val columns = FlatRow.columns(IsoTime(ZoneId.of("Asia/Jakarta")))

    private fun values(row: FlatRow) = columns.associate { it.name to it.value(row) }

    @Test
    fun `A56 tick - serving LTE and the inferred NR cell, CELL_INFO first, SIGNAL_STRENGTH fills gaps`() {
        val v = values(FlatRow.of(measurement(0), a56Cells()))
        assertEquals(479, v["ltePci"])
        assertEquals(1625, v["lteEarfcn"])
        assertEquals("API", v["lteBandSource"])
        assertEquals(-92, v["lteRsrp"]); assertEquals("CELL_INFO", v["lteRsrpSource"])
        assertEquals("RSSNR missing in CellInfo comes from SignalStrength", 15, v["lteSinr"])
        assertEquals("SIGNAL_STRENGTH", v["lteSinrSource"])
        assertEquals(960L, v["lteSignalStrengthAgeMs"])
        assertEquals(13L, v["lteCellInfoAgeMs"])

        assertEquals("neighbor 438 is not taken although the phone labelled it", 440, v["nrPci"])
        assertEquals(1, v["nrServingCount"])
        assertEquals("SIGNAL_STRENGTH_MATCH", v["nrServingSource"])
        assertEquals(41, v["nrBand"])
        assertEquals(-101, v["nrRsrp"]); assertEquals("CELL_INFO", v["nrRsrpSource"])
        assertEquals(7, v["nrSinr"]); assertEquals("CELL_INFO", v["nrSinrSource"])
        assertEquals(-104, v["nrCsiRsrp"]); assertEquals("SIGNAL_STRENGTH", v["nrCsiRsrpSource"])
        assertNull("in neither source: empty value and empty source", v["nrCsiSinr"])
        assertNull(v["nrCsiSinrSource"])
    }

    @Test
    fun `NSA without a NR CellInfo cell - identity empty, signal from SignalStrength`() {
        val v = values(FlatRow.of(measurement(0), a56Cells().filterNot { it.rat == "NR" && it.source == "CELL_INFO" }))
        assertEquals(0, v["nrServingCount"])
        assertNull(v["nrPci"]); assertNull(v["nrServingSource"]); assertNull(v["nrBand"])
        assertEquals(-101, v["nrRsrp"]); assertEquals("SIGNAL_STRENGTH", v["nrRsrpSource"])
    }

    @Test
    fun `NR CA - the strongest serving NR cell, count kept`() {
        val cells = listOf(
            cell("NR", servingSource = "REPORTED", pci = 440, rsrp = -105, arfcn = 531_870),
            cell("NR", servingSource = "REPORTED", pci = 12, rsrp = -95, arfcn = 643_334),
            cell("NR", servingSource = "REPORTED", pci = 13, rsrp = null, arfcn = 650_000),
        )
        val v = values(FlatRow.of(measurement(0), cells))
        assertEquals(12, v["nrPci"])
        assertEquals(3, v["nrServingCount"])
    }

    @Test
    fun `LTE prefers the registered cell over a CA secondary listed first`() {
        val cells = listOf(
            cell("LTE", status = "SECONDARY_SERVING", registered = false, pci = 200, rsrp = -80),
            cell("LTE", status = "NONE", registered = true, pci = 101, rsrp = -95),
        )
        assertEquals(101, values(FlatRow.of(measurement(0), cells))["ltePci"])
    }

    @Test
    fun `no cells and no fix - every radio and location column empty`() {
        val v = values(FlatRow.of(measurement(0, lat = null, lon = null), emptyList()))
        listOf("lat", "lon", "ltePci", "lteRsrp", "lteRsrpSource", "nrPci", "nrRsrp", "nrServingSource").forEach { assertNull(it, v[it]) }
        assertEquals(0, v["nrServingCount"])
        assertEquals("2026-10-14T05:40:00.000+07:00", v["time"])
    }

    @Test
    fun `every value column has a source column right after it`() {
        val names = columns.map { it.name }
        names.filter { it.endsWith("Source") && it != "lteBandSource" && it != "nrBandSource" && it != "nrServingSource" }.forEach { src ->
            assertEquals(src, names.indexOf(src.removeSuffix("Source")) + 1, names.indexOf(src))
        }
        assertEquals("unique headers", names.size, names.toSet().size)
    }
}
