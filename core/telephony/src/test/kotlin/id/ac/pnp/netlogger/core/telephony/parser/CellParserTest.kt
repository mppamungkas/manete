package id.ac.pnp.netlogger.core.telephony.parser

import id.ac.pnp.netlogger.core.model.radio.BandSource
import id.ac.pnp.netlogger.core.model.radio.CellSource
import id.ac.pnp.netlogger.core.model.radio.ConnectionStatus
import id.ac.pnp.netlogger.core.model.radio.Rat
import id.ac.pnp.netlogger.core.model.radio.WarningCode
import id.ac.pnp.netlogger.core.model.raw.CellInfoOrigin
import id.ac.pnp.netlogger.core.model.raw.RawCellInfoResult
import id.ac.pnp.netlogger.core.model.raw.RawLegacyCellLocation
import id.ac.pnp.netlogger.core.model.raw.RawSignalStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellParserTest {

    private val parser = CellParser()

    private fun parse(
        info: RawCellInfoResult,
        ss: RawSignalStrength? = null,
        device: id.ac.pnp.netlogger.core.model.raw.DeviceTraits = MOTO,
        legacy: RawLegacyCellLocation? = null,
        latched: Boolean = false,
    ) = parser.parse(ParseInput(info, ss, device, NOW_MS, legacy, latched))

    @Test
    fun `serving LTE cell maps every field`() {
        val cell = parse(cellInfo(lteCell())).cells.single()

        assertEquals(Rat.LTE, cell.rat)
        assertTrue(cell.isServing)
        assertEquals(true, cell.isRegistered)
        assertEquals(ConnectionStatus.PRIMARY_SERVING, cell.connectionStatus)
        assertEquals(12_345_678L, cell.cellId)
        assertEquals(25_620, cell.tac)
        assertEquals(101, cell.pci)
        assertEquals(1850, cell.arfcn)
        assertEquals(3, cell.band)
        assertEquals(BandSource.API, cell.bandSource)
        assertEquals(20_000, cell.bandwidthKhz)
        assertEquals("510", cell.mcc)
        assertEquals("10", cell.mnc)
        assertEquals(-95, cell.rsrpDbm)
        assertEquals(-11, cell.rsrqDb)
        assertEquals(12, cell.sinrDb)
        assertEquals(-65, cell.rssiDbm)
        assertEquals(9, cell.cqi)
        assertEquals(3, cell.timingAdvance)
        assertEquals(CellSource.CELL_INFO, cell.source)
        assertEquals(200L, cell.cellInfoAgeMs)
    }

    @Test
    fun `sentinels become null without warnings`() {
        val raw = lteCell(
            ci = UNAVAILABLE, tac = UNAVAILABLE, pci = UNAVAILABLE, earfcn = UNAVAILABLE, bandwidthKhz = UNAVAILABLE,
            signal = lteSignal(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE),
            bands = emptyList(), timestamp = Long.MAX_VALUE,
        )
        val result = parse(cellInfo(raw))
        val cell = result.cells.single()

        listOf(cell.cellId, cell.tac, cell.pci, cell.arfcn, cell.band, cell.bandSource, cell.bandwidthKhz,
            cell.rsrpDbm, cell.rsrqDb, cell.sinrDb, cell.rssiDbm, cell.cqi, cell.timingAdvance, cell.cellInfoAgeMs,
        ).forEach { assertNull(it) }
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `implausible values become null with the raw value in a warning`() {
        val result = parse(cellInfo(lteCell(signal = lteSignal(rsrp = 464, rssnr = 300))))
        val cell = result.cells.single()

        assertNull(cell.rsrpDbm)
        assertNull(cell.sinrDb)
        assertEquals(-11, cell.rsrqDb)
        val warnings = result.warnings.filter { it.code == WarningCode.OUT_OF_RANGE }
        assertEquals(setOf("rsrp" to "464", "rssnr" to "300"), warnings.map { it.field to it.rawValue }.toSet())
        assertTrue(warnings.all { it.rat == Rat.LTE && it.pci == 101 })
    }

    @Test
    fun `empty getBands leaves band null until a mapper is configured`() {
        val cell = parse(cellInfo(lteCell(bands = emptyList()))).cells.single()
        assertNull(cell.band)
        assertNull(cell.bandSource)
    }

    @Test
    fun `mapper fills band when getBands is empty and marks it MAPPED`() {
        val mapper = BandMapper { rat, arfcn -> if (rat == Rat.LTE && arfcn == 1850) 3 else null }
        val cell = CellParser(mapper).parse(ParseInput(cellInfo(lteCell(bands = null)), null, MOTO, NOW_MS)).cells.single()
        assertEquals(3, cell.band)
        assertEquals(BandSource.MAPPED, cell.bandSource)
    }

    @Test
    fun `multiple and invalid bands are reported`() {
        val result = parse(cellInfo(lteCell(bands = listOf(0, 3, 1))))
        assertEquals(3, result.cells.single().band)
        assertEquals(setOf(WarningCode.INVALID_BAND, WarningCode.MULTIPLE_BANDS), result.warnings.map { it.code }.toSet())
    }

    @Test
    fun `neighbors are not serving`() {
        val result = parse(cellInfo(lteCell(), lteCell(registered = false, pci = 102)))
        val neighbor = result.cells.single { it.pci == 102 }
        assertFalse(neighbor.isServing)
        assertEquals(ConnectionStatus.NONE, neighbor.connectionStatus)
    }

    @Test
    fun `NSA without CellInfoNr takes NR signal from SignalStrength`() {
        val result = parse(cellInfo(lteCell()), signalStrength(lte = listOf(lteSignal()), nr = listOf(nrSignal())))

        val nr = result.cells.single { it.rat == Rat.NR }
        assertEquals(CellSource.SIGNAL_STRENGTH, nr.source)
        assertTrue(nr.isServing)
        assertNull(nr.isRegistered)
        assertNull(nr.pci)
        assertEquals(-100, nr.rsrpDbm)
        assertEquals(50L, nr.cellInfoAgeMs)
        assertTrue(result.nrSignalStrengthPresent)
        assertEquals(listOf(CellSource.CELL_INFO, CellSource.SIGNAL_STRENGTH), result.cells.filter { it.rat == Rat.LTE }.map { it.source })
    }

    @Test
    fun `CellInfoNr and SignalStrength NR are both kept, not deduplicated`() {
        val result = parse(cellInfo(lteCell(), nrCell()), signalStrength(nr = listOf(nrSignal(ssRsrp = -90))))

        val nr = result.cells.single { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }
        assertEquals(ConnectionStatus.SECONDARY_SERVING, nr.connectionStatus)
        assertEquals(500, nr.pci)
        assertEquals(40, nr.band)
        assertEquals(-100, nr.rsrpDbm)
        assertNull("NCI Long.MAX_VALUE is a sentinel", nr.cellId)
        val ss = result.cells.single { it.rat == Rat.NR && it.source == CellSource.SIGNAL_STRENGTH }
        assertEquals(-90, ss.rsrpDbm)
        assertNull("identity is never copied from CellInfo", ss.pci)
        assertTrue(result.nrSignalStrengthPresent)
    }

    @Test
    fun `moto g45 - RSSNR missing in CellInfo is kept from SignalStrength in its own row`() {
        // dumpsys on moto g45 (Tri, B3, 2026-10-13): CellInfo rssnr=MAX, SignalStrength.mLte rssnr=11.
        // Same pattern in src/test/resources/fixtures/moto-g45_tri_lte-ca_2026-10-13.txt (rssnr=9).
        val cell = lteCell(timestamp = NOW_MS - 986, pci = 479, earfcn = 1475, signal = lteSignal(-101, -13, UNAVAILABLE, -69, UNAVAILABLE, UNAVAILABLE))
        val ss = signalStrength(lte = listOf(lteSignal(-103, -11, 11, -71, UNAVAILABLE, UNAVAILABLE)), timestamp = NOW_MS - 300)
        val result = parse(cellInfo(cell), ss)

        val fromCellInfo = result.cells.single { it.rat == Rat.LTE && it.source == CellSource.CELL_INFO }
        val fromSs = result.cells.single { it.rat == Rat.LTE && it.source == CellSource.SIGNAL_STRENGTH }
        assertNull(fromCellInfo.sinrDb)
        assertEquals(-101, fromCellInfo.rsrpDbm)
        assertEquals(986L, fromCellInfo.cellInfoAgeMs)

        assertTrue(fromSs.isServing)
        assertEquals(11, fromSs.sinrDb)
        assertEquals("both RSRP values are kept", -103, fromSs.rsrpDbm)
        assertEquals("age from SignalStrength.getTimestampMillis()", 300L, fromSs.cellInfoAgeMs)
        assertNull(fromSs.pci)
        assertNull(fromSs.arfcn)
        assertNull(fromSs.cellId)
        assertNull(fromSs.band)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `CellInfoNr without signal gets a SignalStrength row next to it`() {
        val empty = nrSignal(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE)
        val result = parse(cellInfo(lteCell(), nrCell(signal = empty)), signalStrength(nr = listOf(nrSignal())))
        assertEquals(setOf(CellSource.CELL_INFO, CellSource.SIGNAL_STRENGTH), result.cells.filter { it.rat == Rat.NR }.map { it.source }.toSet())
    }

    @Test
    fun `SignalStrength with only sentinel NR values is not NR evidence`() {
        val result = parse(cellInfo(lteCell()), signalStrength(nr = listOf(nrSignal(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))))
        assertFalse(result.nrSignalStrengthPresent)
        assertTrue(result.cells.none { it.rat == Rat.NR })
    }

    @Test
    fun `negative cell age is rejected`() {
        val result = parse(cellInfo(lteCell(timestamp = NOW_MS + 5_000)))
        assertNull(result.cells.single().cellInfoAgeMs)
        assertEquals(WarningCode.OUT_OF_RANGE, result.warnings.single().code)
    }

    @Test
    fun `stale cached cells keep their age`() {
        val result = parse(cellInfo(lteCell(timestamp = NOW_MS - 12_000), origin = CellInfoOrigin.CACHED))
        assertEquals(12_000L, result.cells.single().cellInfoAgeMs)
    }

    @Test
    fun `unavailable cell info and extraction failures are warnings, not exceptions`() {
        val info = RawCellInfoResult(emptyList(), CellInfoOrigin.UNAVAILABLE, errorCode = -2, unsupportedCells = 1, extractionFailures = 3)
        val codes = parse(info).warnings.map { it.code to it.rawValue }
        assertTrue(WarningCode.CELL_INFO_UNAVAILABLE to "-2" in codes)
        assertTrue(WarningCode.EXTRACTION_FAILED to "3" in codes)
        assertTrue(WarningCode.UNSUPPORTED_CELL_TYPE to "1" in codes)
    }

    @Test
    fun `WCDMA and GSM map their own fields`() {
        val cells = parse(cellInfo(wcdmaCell(registered = true), gsmCell())).cells
        val wcdma = cells.single { it.rat == Rat.WCDMA }
        assertEquals(-85, wcdma.rscpDbm)
        assertEquals(-8, wcdma.ecNoDb)
        assertEquals(200, wcdma.pci)
        val gsm = cells.single { it.rat == Rat.GSM }
        assertEquals(-70, gsm.rssiDbm)
        assertEquals(30, gsm.pci)
        assertEquals(2, gsm.timingAdvance)
    }

    @Test
    fun `invalid PLMN strings are dropped`() {
        val raw = lteCell().copy(mcc = "51O", mnc = "")
        val result = parse(cellInfo(raw))
        assertNull(result.cells.single().mcc)
        assertNull(result.cells.single().mnc)
        assertEquals(listOf("mcc"), result.warnings.map { it.field })
    }

    // --- Samsung quirks ---

    @Test
    fun `Samsung TAC byte swap is detected from getCellLocation and latched`() {
        // CellInfo says 5220 (0x1464); getCellLocation says 25620 (0x6414).
        val legacy = RawLegacyCellLocation(cid = 12_345_678, lac = 25_620)
        val first = parse(cellInfo(lteCell(tac = 5_220)), device = SAMSUNG, legacy = legacy)

        assertEquals(25_620, first.cells.single().tac)
        assertTrue(first.samsungTacSwapLatched)
        val warning = first.warnings.single()
        assertEquals(WarningCode.SAMSUNG_TAC_BYTE_SWAP, warning.code)
        assertEquals("5220", warning.rawValue)

        val next = parse(cellInfo(lteCell(tac = 5_220)), device = SAMSUNG, legacy = null, latched = first.samsungTacSwapLatched)
        assertEquals(25_620, next.cells.single().tac)
    }

    @Test
    fun `TAC is left alone when cell IDs differ, on non-Samsung, and for neighbors`() {
        val legacy = RawLegacyCellLocation(cid = 999, lac = 25_620)
        assertEquals(5_220, parse(cellInfo(lteCell(tac = 5_220)), device = SAMSUNG, legacy = legacy).cells.single().tac)

        val matching = RawLegacyCellLocation(cid = 12_345_678, lac = 25_620)
        assertEquals(5_220, parse(cellInfo(lteCell(tac = 5_220)), device = MOTO, legacy = matching).cells.single().tac)

        val neighbor = parse(cellInfo(lteCell(registered = false, tac = 5_220)), device = SAMSUNG, latched = true)
        assertEquals(5_220, neighbor.cells.single().tac)
    }

    @Test
    fun `Samsung neighbor placeholders are dropped and recorded`() {
        val result = parse(
            cellInfo(lteCell(registered = false, signal = lteSignal(rssi = -51, ta = 0)), wcdmaCell(ecNo = 0), gsmCell(rssi = -51, ta = 0)),
            device = SAMSUNG,
        )
        result.cells.forEach { cell ->
            assertNull(cell.rssiDbm)
            assertNull(cell.timingAdvance)
            assertNull(cell.ecNoDb)
        }
        assertEquals(5, result.warnings.count { it.code == WarningCode.SAMSUNG_NEIGHBOR_PLACEHOLDER })
    }

    @Test
    fun `placeholder values are kept on serving cells and on other manufacturers`() {
        val serving = parse(cellInfo(lteCell(signal = lteSignal(rssi = -51, ta = 0))), device = SAMSUNG).cells.single()
        assertEquals(-51, serving.rssiDbm)
        assertEquals(0, serving.timingAdvance)

        val moto = parse(cellInfo(lteCell(registered = false, signal = lteSignal(rssi = -51, ta = 0))), device = MOTO)
        assertEquals(-51, moto.cells.single().rssiDbm)
        assertTrue(moto.warnings.isEmpty())
    }

    @Test
    fun `Samsung LTE SNR zero is treated as unavailable, elsewhere it is a value`() {
        val samsung = parse(cellInfo(lteCell(signal = lteSignal(rssnr = 0))), device = SAMSUNG)
        assertNull(samsung.cells.single().sinrDb)
        assertEquals(WarningCode.SAMSUNG_LTE_SNR_ZERO, samsung.warnings.single().code)

        assertEquals(0, parse(cellInfo(lteCell(signal = lteSignal(rssnr = 0))), device = MOTO).cells.single().sinrDb)
    }

    @Test
    fun `byte swap helpers`() {
        assertEquals(0x6414, SamsungQuirks.swapBytes(0x1464))
        assertTrue(SamsungQuirks.isByteSwapped(5_220, 25_620))
        assertFalse(SamsungQuirks.isByteSwapped(0x1414, 0x1414))
        assertFalse(SamsungQuirks.isByteSwapped(70_000, 25_620))
    }
}
