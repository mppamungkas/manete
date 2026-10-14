package id.my.pamslab.netlogger.core.telephony.parser

import id.my.pamslab.netlogger.core.model.radio.BandSource
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.radio.ServingSource
import id.my.pamslab.netlogger.core.model.radio.WarningCode
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.raw.RawCell
import id.my.pamslab.netlogger.core.model.raw.RawCellInfoResult
import id.my.pamslab.netlogger.core.model.raw.RawLegacyCellLocation
import id.my.pamslab.netlogger.core.model.raw.RawNrCell
import id.my.pamslab.netlogger.core.model.raw.RawNrSignal
import id.my.pamslab.netlogger.core.model.raw.RawSignalStrength
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
        device: id.my.pamslab.netlogger.core.model.raw.DeviceTraits = MOTO,
        legacy: RawLegacyCellLocation? = null,
        latched: Boolean = false,
        bandwidths: List<Int>? = null,
        override: Int? = null,
        mcc: String? = null,
        parser: CellParser = this.parser,
    ) = parser.parse(ParseInput(info, ss, device, NOW_MS, legacy, latched, bandwidths, override, mcc))

    @Test
    fun `serving LTE cell maps every field`() {
        val cell = parse(cellInfo(lteCell())).cells.single()

        assertEquals(Rat.LTE, cell.rat)
        assertTrue(cell.servingInferred)
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
        val mapper = BandMapper { rat, arfcn, _ -> if (rat == Rat.LTE && arfcn == 1850) BandMapping(3, listOf(3)) else null }
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
        assertFalse(neighbor.servingInferred)
        assertEquals(ConnectionStatus.NONE, neighbor.connectionStatus)
    }

    @Test
    fun `NSA without CellInfoNr takes NR signal from SignalStrength`() {
        val result = parse(cellInfo(lteCell()), signalStrength(lte = listOf(lteSignal()), nr = listOf(nrSignal())))

        val nr = result.cells.single { it.rat == Rat.NR }
        assertEquals(CellSource.SIGNAL_STRENGTH, nr.source)
        assertTrue(nr.servingInferred)
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

        assertTrue(fromSs.servingInferred)
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
    fun `A56 - same-carrier SECONDARY_SERVING neighbors without CA become neighbors, placeholders dropped`() {
        // A56, Telkomsel B1, 2026-10-13: three cells with status 2 on the PCell's EARFCN 500, while
        // getCellBandwidths() = [20000] and override NONE. CI/TAC 0, CQI 0, TA 0, RSSNR 0 on all three.
        val placeholder = { rsrp: Int -> lteSignal(rsrp, -17, 0, -77, 0, 0) }
        fun fake(pci: Int, rsrp: Int) = lteCell(registered = false, status = 2, ci = 0, tac = 0, pci = pci, earfcn = 500, bands = listOf(1), signal = placeholder(rsrp))
        val primary = lteCell(ci = 158_896_673, tac = 11_550, pci = 140, earfcn = 500, bands = listOf(1), bandwidthKhz = UNAVAILABLE, signal = lteSignal(-101, -14, 5, -79, 12, 1))
        val result = parse(cellInfo(primary, fake(139, -99), fake(138, -100), fake(290, -107)), device = SAMSUNG, bandwidths = listOf(20_000), override = 0)

        val serving = result.cells.single { it.servingInferred }
        assertEquals(140, serving.pci)
        assertEquals(12, serving.cqi)
        assertEquals(1, serving.timingAdvance)
        assertEquals(5, serving.sinrDb)
        val neighbors = result.cells.filterNot { it.servingInferred }
        assertEquals(listOf(139, 138, 290), neighbors.map { it.pci })
        neighbors.forEach { n ->
            assertEquals(ConnectionStatus.NONE, n.connectionStatus)
            assertNull(n.cellId)
            assertNull(n.tac)
            assertNull(n.cqi)
            assertNull(n.timingAdvance)
            assertNull(n.sinrDb)
            assertEquals(-77, n.rssiDbm)
            assertEquals(1, n.band)
        }
        val codes = result.warnings.groupingBy { it.code }.eachCount()
        assertEquals(3, codes[WarningCode.SECONDARY_SERVING_WITHOUT_CA])
        assertEquals(3, codes[WarningCode.SAMSUNG_LTE_SNR_ZERO])
        assertEquals(12, codes[WarningCode.SAMSUNG_NEIGHBOR_PLACEHOLDER])
        assertEquals("2", result.warnings.first { it.code == WarningCode.SECONDARY_SERVING_WITHOUT_CA }.rawValue)
    }

    @Test
    fun `real CA secondary on another carrier stays serving`() {
        val scell = lteCell(registered = false, status = 2, pci = 200, earfcn = 1850)
        val primary = lteCell(earfcn = 500)
        listOf(
            parse(cellInfo(primary, scell), bandwidths = listOf(20_000, 20_000)),
            parse(cellInfo(primary, scell), override = 1),
            parse(cellInfo(primary, scell)), // CA unknown: keep what the phone says
        ).forEach { result ->
            assertEquals(ConnectionStatus.SECONDARY_SERVING, result.cells.single { it.pci == 200 }.connectionStatus)
            assertTrue(result.cells.single { it.pci == 200 }.servingInferred)
            assertTrue(result.warnings.none { it.code == WarningCode.SECONDARY_SERVING_WITHOUT_CA })
        }
    }

    @Test
    fun `secondary without CA is demoted on every manufacturer, on the same carrier even if CA is unknown`() {
        val primary = lteCell(earfcn = 500)
        val noCa = parse(cellInfo(primary, lteCell(registered = false, status = 2, pci = 200, earfcn = 1850)), bandwidths = listOf(20_000))
        assertFalse(noCa.cells.single { it.pci == 200 }.servingInferred)
        val sameCarrier = parse(cellInfo(primary, lteCell(registered = false, status = 2, pci = 200, earfcn = 500)))
        assertFalse(sameCarrier.cells.single { it.pci == 200 }.servingInferred)
        // Placeholder rules stay Samsung-only.
        assertEquals(0, noCa.warnings.count { it.code == WarningCode.SAMSUNG_NEIGHBOR_PLACEHOLDER })
    }

    @Test
    fun `non-LTE bandwidths are not CA evidence for an LTE primary`() {
        // A56, Tri, 2026-10-13: getCellBandwidths() = [10000, 10000, 60000] with CA off and one 10 MHz carrier.
        val primary = lteCell(earfcn = 1475)
        val scell = lteCell(registered = false, status = 2, pci = 200, earfcn = 1850)
        val a56 = parse(cellInfo(primary, scell), bandwidths = listOf(10_000, 60_000), override = 0)
        assertFalse(a56.cells.single { it.pci == 200 }.servingInferred)
        assertEquals(1, a56.warnings.count { it.code == WarningCode.SECONDARY_SERVING_WITHOUT_CA })
        // Two LTE-sized carriers are still CA evidence.
        val ca = parse(cellInfo(primary, scell), bandwidths = listOf(10_000, 10_000, 60_000), override = 0)
        assertTrue(ca.cells.single { it.pci == 200 }.servingInferred)
    }

    @Test
    fun `NR secondary under an LTE primary is EN-DC and is never demoted`() {
        val result = parse(cellInfo(lteCell(), nrCell()), bandwidths = listOf(20_000), override = 0)
        val nr = result.cells.single { it.rat == Rat.NR }
        assertTrue(nr.servingInferred)
        assertEquals(ConnectionStatus.SECONDARY_SERVING, nr.connectionStatus)
    }

    // moto g45, Tri (510-89), 5G NSA, 2026-10-13: CellInfoNr unregistered with status NONE, PCI 479, NR-ARFCN 531870,
    // bands [], TAC copied from LTE (19350), no signal. NR signal only in SignalStrength. LTE anchor: registered,
    // status NONE (Qualcomm doesn't fill it).
    private val motoAnchor = lteCell(status = 0, timestamp = NOW_MS - 6, ci = 12_866_878, tac = 19_350, pci = 479, earfcn = 1475,
        bandwidthKhz = 10_000, signal = lteSignal(-96, -9, UNAVAILABLE, -71, UNAVAILABLE, 1))
    private val motoNr = RawNrCell(false, 0, NOW_MS - 6, Long.MAX_VALUE, 19_350, 479, 531_870, emptyList(), null, null,
        RawNrSignal(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))
    private val nsaParser = CellParser(BandMapper.Standard)

    private fun motoNsa(nrSignal: RawNrSignal, override: Int? = 3, vararg extraNr: RawNrCell) = parse(
        cellInfo(motoAnchor, *(listOf<RawCell>(motoNr) + extraNr).toTypedArray()),
        signalStrength(lte = listOf(lteSignal(-97, -13, 9, -67, UNAVAILABLE, UNAVAILABLE)), nr = listOf(nrSignal), timestamp = NOW_MS - 1_263),
        bandwidths = listOf(10_000, 10_000, 20_000), override = override, mcc = "510", parser = nsaParser,
    )

    @Test
    fun `moto g45 NSA - unregistered NR cell becomes the NSA secondary, TAC blanked, band n41 mapped`() {
        listOf(
            RawNrSignal(-101, -11, 17, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), // capture 1
            RawNrSignal(-103, -12, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), // capture 2
        ).forEach { ssNr ->
            val result = motoNsa(ssNr)
            val nrCell = result.cells.single { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }
            assertTrue(nrCell.servingInferred)
            assertEquals(ConnectionStatus.SECONDARY_SERVING, nrCell.connectionStatus)
            assertNull("PCI copied from LTE together with the TAC is blanked", nrCell.pci)
            assertEquals(531_870, nrCell.arfcn)
            assertEquals(41, nrCell.band)
            assertEquals(BandSource.MAPPED, nrCell.bandSource)
            assertNull("TAC copied from LTE is blanked", nrCell.tac)
            assertNull("signal is not copied from SignalStrength", nrCell.rsrpDbm)
            assertEquals(false, nrCell.reportedServing)
            assertEquals(ServingSource.NSA_INFERRED, nrCell.servingSource)

            val nrSs = result.cells.single { it.rat == Rat.NR && it.source == CellSource.SIGNAL_STRENGTH }
            assertEquals(ssNr.ssRsrp, nrSs.rsrpDbm)
            assertEquals(ssNr.ssSinr.takeIf { it != UNAVAILABLE }, nrSs.sinrDb)
            assertNull(nrSs.pci)
            assertEquals(1_263L, nrSs.cellInfoAgeMs)

            val warnings = result.warnings.associateBy { it.code }
            assertEquals("0", warnings.getValue(WarningCode.NR_NSA_SECONDARY_INFERRED).rawValue)
            assertEquals("19350", warnings.getValue(WarningCode.NR_TAC_FROM_LTE).rawValue)
            assertEquals("479", warnings.getValue(WarningCode.NR_PCI_FROM_LTE).rawValue)
            assertEquals("[7, 41, 90]", warnings.getValue(WarningCode.MULTIPLE_BANDS).rawValue)

            val inputs = NetworkClassifier.nsaInputs(result.cells, result.nrSignalStrengthPresent, 3, 13)
            assertTrue(inputs.nrServingCellInfo)
            assertEquals(NetworkType.NR_NSA, NetworkClassifier.classify(inputs, result.cells, 0))
        }
    }

    @Test
    fun `NR cell is not labelled without an NSA override`() {
        listOf(null, 0, 1).forEach { override ->
            val result = motoNsa(nrSignal(), override = override)
            val nrCell = result.cells.single { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }
            assertFalse(nrCell.servingInferred)
            assertEquals(19_350, nrCell.tac)
            assertTrue(result.warnings.none { it.code == WarningCode.NR_NSA_SECONDARY_INFERRED || it.code == WarningCode.NR_TAC_FROM_LTE })
        }
    }

    @Test
    fun `several unregistered NR cells in NSA are not guessed`() {
        val second = motoNr.copy(pci = 480, tac = UNAVAILABLE)
        val result = motoNsa(nrSignal(), 3, second)
        val nr = result.cells.filter { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }
        assertTrue(nr.none { it.servingInferred })
        assertTrue(nr.all { it.servingSource == ServingSource.AMBIGUOUS })
        assertEquals("2", result.warnings.single { it.code == WarningCode.NR_NSA_SECONDARY_AMBIGUOUS }.rawValue)
    }

    @Test
    fun `NR leg dropped - SignalStrength NR unavailable leaves no NR serving row`() {
        // Seen a minute later: override back to LTE_CA, mNr all unavailable, CellInfoNr gone.
        val result = parse(
            cellInfo(motoAnchor),
            signalStrength(lte = listOf(lteSignal()), nr = listOf(RawNrSignal(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))),
            override = 1, mcc = "510", parser = nsaParser,
        )
        assertTrue(result.cells.none { it.rat == Rat.NR })
        assertFalse(result.nrSignalStrengthPresent)
    }

    @Test
    fun `NR TAC that differs from the LTE TAC is kept, and then an equal PCI is kept too`() {
        val result = parse(
            cellInfo(motoAnchor, motoNr.copy(tac = 77)), override = 3, mcc = "510", parser = nsaParser,
        )
        val nr = result.cells.single { it.rat == Rat.NR }
        assertEquals(77, nr.tac)
        assertEquals("co-sited NR may share the PCI", 479, nr.pci)
        assertTrue(result.warnings.none { it.code == WarningCode.NR_PCI_FROM_LTE || it.code == WarningCode.NR_TAC_FROM_LTE })
    }

    // A56 (Samsung, Android 16), Tri, same site in NSA, 2026-10-13. PhysicalChannelConfig (dumpsys only): NR PCI 440,
    // n41, 60 MHz. CellInfoNr: registered NO, status 2, PCI 440, NR-ARFCN 531870, bands [7], TAC 0, SS -99/-11/12,
    // CSI -140/-20/-23.
    private fun a56Nsa(device: id.my.pamslab.netlogger.core.model.raw.DeviceTraits = SAMSUNG, mcc: String? = "510") = parse(
        cellInfo(
            lteCell(ci = 12_866_888, tac = 19_350, pci = 479, earfcn = 1625, bandwidthKhz = UNAVAILABLE, signal = lteSignal(-92, -7, 14, -63, 9, 2)),
            RawNrCell(false, 2, NOW_MS - 994, Long.MAX_VALUE, 0, 440, 531_870, listOf(7), null, null, RawNrSignal(-99, -11, 12, -140, -20, -23)),
        ),
        signalStrength(lte = listOf(lteSignal(-93, -9, 15, -93, 13, UNAVAILABLE)), nr = listOf(RawNrSignal(-100, -10, 14, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))),
        device = device, bandwidths = listOf(20_000, 10_000, 60_000), override = 3, mcc = mcc, parser = nsaParser,
    )

    @Test
    fun `A56 NSA - band n7 from getBands is replaced by n41, TAC 0 and CSI floor blanked`() {
        val result = a56Nsa()
        val nr = result.cells.single { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }
        assertTrue(nr.servingInferred)
        assertEquals(ConnectionStatus.SECONDARY_SERVING, nr.connectionStatus)
        assertEquals(440, nr.pci)
        assertEquals(41, nr.band)
        assertEquals(BandSource.MAPPED, nr.bandSource)
        assertNull(nr.tac)
        assertEquals(-99, nr.rsrpDbm)
        assertEquals(12, nr.sinrDb)
        assertNull(nr.csiRsrpDbm)
        assertNull(nr.csiRsrqDb)
        assertNull(nr.csiSinrDb)
        assertEquals(true, nr.reportedServing)
        assertEquals("single candidate keeps the phone's label", ServingSource.REPORTED, nr.servingSource)

        val codes = result.warnings.groupingBy { it.code }.eachCount()
        assertEquals(1, codes[WarningCode.API_BAND_CONFLICT])
        assertEquals("[7]", result.warnings.single { it.code == WarningCode.API_BAND_CONFLICT }.rawValue)
        assertEquals(1, codes[WarningCode.NR_NSA_TAC_ZERO])
        assertEquals(3, codes[WarningCode.SAMSUNG_NR_CSI_PLACEHOLDER])
        assertNull("reported as secondary, nothing inferred", codes[WarningCode.NR_NSA_SECONDARY_INFERRED])
        assertNull(codes[WarningCode.NR_PCI_FROM_LTE])
        assertEquals(-100, result.cells.single { it.rat == Rat.NR && it.source == CellSource.SIGNAL_STRENGTH }.rsrpDbm)
    }

    @Test
    fun `API band is kept outside countries with a known band, and CSI floor is kept on other manufacturers`() {
        val elsewhere = a56Nsa(mcc = "262").cells.single { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }
        assertEquals(7, elsewhere.band)
        assertEquals(BandSource.API, elsewhere.bandSource)

        val moto = a56Nsa(device = MOTO).cells.single { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }
        assertEquals(-140, moto.csiRsrpDbm)
        assertEquals(-20, moto.csiRsrqDb)
        assertNull("TAC 0 rule is for every manufacturer", moto.tac)
    }

    @Test
    fun `CSI is kept when RSRP is close to SS-RSRP, even at the RSRQ and SINR floor`() {
        val cell = SamsungQuirks.fixNrCsiFloor(
            parse(cellInfo(lteCell(), nrCell(signal = RawNrSignal(-100, -11, 5, -105, -20, -23)))).cells.single { it.rat == Rat.NR },
            mutableListOf(),
        )
        assertEquals(-105, cell.csiRsrpDbm)
    }

    @Test
    fun `API band containing the preferred band keeps API source`() {
        val result = parse(cellInfo(lteCell(), nrCell(arfcn = 531_870, bands = listOf(7, 41))), mcc = "510", parser = nsaParser)
        val nr = result.cells.single { it.rat == Rat.NR }
        assertEquals(41, nr.band)
        assertEquals(BandSource.API, nr.bandSource)
        assertTrue(result.warnings.none { it.code == WarningCode.API_BAND_CONFLICT })
    }

    // A56, Tri, walk 2026-10-14: every NR cell on NR-ARFCN 531870 is reported as SECONDARY_SERVING, unregistered. PCI 440
    // is the real NSA secondary (PhysicalChannelConfig); 438, 161, 169 and 439 are neighbors. Values from the field log.
    private fun a56Nr(pci: Int, rsrp: Int, rsrq: Int, sinr: Int, arfcn: Int = 531_870) =
        RawNrCell(false, 2, NOW_MS - 13, Long.MAX_VALUE, 0, pci, arfcn, listOf(7), null, null, RawNrSignal(rsrp, rsrq, sinr, -140, -20, -23))

    private fun a56Multi(vararg nr: RawNrCell, ssNr: RawNrSignal? = null) = parse(
        cellInfo(lteCell(ci = 12_866_888, tac = 19_350, pci = 477, earfcn = 1625, bandwidthKhz = UNAVAILABLE), *nr),
        signalStrength(lte = listOf(lteSignal()), nr = listOfNotNull(ssNr)),
        device = SAMSUNG, bandwidths = listOf(20_000, 60_000), override = 3, mcc = "510", parser = nsaParser,
    )

    private fun ParseResult.nrCells() = cells.filter { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }.associateBy { it.pci }

    @Test
    fun `A56 - the candidate matching SignalStrength NR is serving, the same-channel neighbor is not`() {
        val result = a56Multi(a56Nr(440, -101, -11, 7), a56Nr(438, -109, -18, -7), ssNr = RawNrSignal(-101, -11, 9, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))
        val nr = result.nrCells()
        assertTrue(nr.getValue(440).servingInferred)
        assertEquals(ConnectionStatus.SECONDARY_SERVING, nr.getValue(440).connectionStatus)
        assertFalse(nr.getValue(438).servingInferred)
        assertEquals(ConnectionStatus.NONE, nr.getValue(438).connectionStatus)
        nr.values.forEach {
            assertEquals("the phone's label is kept", true, it.reportedServing)
            assertEquals(ServingSource.SIGNAL_STRENGTH_MATCH, it.servingSource)
        }
        val w = result.warnings.single { it.code == WarningCode.NR_SERVING_SIGNAL_STRENGTH_MATCH }
        assertEquals(438, w.pci)
        assertEquals("2", w.rawValue)
        assertEquals("TAC 0 only blanked on the kept cell", listOf(440), result.warnings.filter { it.code == WarningCode.NR_NSA_TAC_ZERO }.map { it.pci })
        assertNull("as a neighbor its TAC 0 is a Samsung placeholder", nr.getValue(438).tac)
        assertTrue(result.warnings.any { it.code == WarningCode.SAMSUNG_NEIGHBOR_PLACEHOLDER && it.pci == 438 && it.field == "tac" })
    }

    @Test
    fun `A56 - SignalStrength 1 dB off still matches, more than 1 dB does not`() {
        val near = a56Multi(a56Nr(440, -106, -12, 5), a56Nr(438, -111, -17, -5), ssNr = RawNrSignal(-105, -11, 6, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))
        assertEquals(ServingSource.SIGNAL_STRENGTH_MATCH, near.nrCells().getValue(440).servingSource)
        val far = a56Multi(a56Nr(440, -106, -12, 5), a56Nr(438, -111, -17, -5), ssNr = RawNrSignal(-108, -12, 6, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))
        assertEquals(ServingSource.STRONGEST, far.nrCells().getValue(440).servingSource)
    }

    @Test
    fun `A56 - without a SignalStrength match the strongest of PCIs 440, 438, 161, 169, 439 is serving`() {
        val result = a56Multi(
            a56Nr(438, -111, -17, -5), a56Nr(440, -85, -11, 6), a56Nr(161, -113, -19, 1), a56Nr(169, -115, -19, -2), a56Nr(439, -112, -18, -3),
            ssNr = RawNrSignal(-101, -11, 9, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), // stale, matches none
        )
        val nr = result.nrCells()
        assertEquals(listOf(440), nr.values.filter { it.servingInferred }.map { it.pci })
        assertTrue(nr.values.all { it.servingSource == ServingSource.STRONGEST && it.reportedServing == true })
        assertEquals(listOf(438, 161, 169, 439), result.warnings.filter { it.code == WarningCode.NR_SERVING_STRONGEST }.map { it.pci })

        val noSignalStrength = a56Multi(a56Nr(440, -101, -11, 7), a56Nr(438, -109, -18, -7))
        assertEquals(ServingSource.STRONGEST, noSignalStrength.nrCells().getValue(440).servingSource)
        assertTrue(noSignalStrength.nrCells().getValue(440).servingInferred)

        val inputs = NetworkClassifier.nsaInputs(result.cells, result.nrSignalStrengthPresent, 3, 13)
        assertTrue(inputs.nrServingCellInfo)
    }

    @Test
    fun `A56 - two SignalStrength matches fall back to the strongest`() {
        val result = a56Multi(a56Nr(440, -101, -11, 7), a56Nr(438, -102, -12, 3), ssNr = RawNrSignal(-101, -11, 9, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))
        assertEquals(ServingSource.STRONGEST, result.nrCells().getValue(440).servingSource)
        assertTrue(result.nrCells().getValue(440).servingInferred)
    }

    @Test
    fun `equal strongest candidates without a match are ambiguous and none is serving`() {
        val result = a56Multi(a56Nr(440, -105, -11, 6), a56Nr(438, -105, -17, -5))
        val nr = result.nrCells()
        assertTrue(nr.values.none { it.servingInferred })
        assertTrue(nr.values.all { it.servingSource == ServingSource.AMBIGUOUS && it.reportedServing == true })
        assertEquals(setOf(440, 438), result.warnings.filter { it.code == WarningCode.NR_SERVING_AMBIGUOUS }.map { it.pci }.toSet())
        assertTrue("no serving NR left for TAC blanking", result.warnings.none { it.code == WarningCode.NR_NSA_TAC_ZERO })
    }

    @Test
    fun `candidates on different NR channels are all kept as reported`() {
        val result = a56Multi(a56Nr(440, -101, -11, 7), a56Nr(438, -109, -18, -7, arfcn = 643_334))
        assertTrue(result.nrCells().values.all { it.servingInferred && it.servingSource == ServingSource.REPORTED })
        assertTrue(result.warnings.none { it.code.name.startsWith("NR_SERVING_") })
    }

    @Test
    fun `LTE cells and SignalStrength rows carry no serving source`() {
        val result = a56Multi(a56Nr(440, -101, -11, 7), ssNr = RawNrSignal(-101, -11, 9, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE))
        val lte = result.cells.single { it.rat == Rat.LTE && it.source == CellSource.CELL_INFO }
        assertEquals(true, lte.reportedServing)
        assertNull(lte.servingSource)
        result.cells.filter { it.source == CellSource.SIGNAL_STRENGTH }.forEach {
            assertTrue(it.servingInferred)
            assertNull(it.reportedServing)
            assertNull(it.servingSource)
        }
    }

    @Test
    fun `A56 first tick - without the network MCC the registered cell's MCC picks n41, marked MAPPED_MCC_FALLBACK`() {
        val nr = a56Nsa(mcc = null).cells.single { it.rat == Rat.NR && it.source == CellSource.CELL_INFO }
        assertEquals(41, nr.band)
        assertEquals(BandSource.MAPPED_MCC_FALLBACK, nr.bandSource)

        // The fallback only marks bands the MCC chose: an unambiguous mapped band stays MAPPED.
        val plain = parse(cellInfo(lteCell(), nrCell(arfcn = 643_334, bands = emptyList())), parser = nsaParser)
            .cells.single { it.rat == Rat.NR }
        assertEquals(BandSource.MAPPED, plain.bandSource)

        // No MCC anywhere: getBands() is kept.
        val noMcc = parse(
            cellInfo(lteCell().copy(mcc = null), RawNrCell(false, 2, NOW_MS, Long.MAX_VALUE, 0, 440, 531_870, listOf(7), null, null, nrSignal())),
            parser = nsaParser,
        ).cells.single { it.rat == Rat.NR }
        assertEquals(7, noMcc.band)
        assertEquals(BandSource.API, noMcc.bandSource)
    }

    // A56 drive, 2026-10-14, screen off: the NR leg was released (override 0, no CellInfoNr), but SignalStrength kept
    // its last NR value (-104 / -11 / 9) for up to 110 s.
    private fun releasedNr(ssAgeMs: Long, override: Int? = 0, vararg nr: RawNrCell) = parse(
        cellInfo(lteCell(pci = 479, earfcn = 1625), *nr),
        signalStrength(
            lte = listOf(lteSignal(-99, -11, 12, -70, 9, UNAVAILABLE)),
            nr = listOf(RawNrSignal(-104, -11, 9, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE)),
            timestamp = NOW_MS - ssAgeMs,
        ),
        device = SAMSUNG, bandwidths = listOf(20_000), override = override, mcc = "510", parser = nsaParser,
    )

    @Test
    fun `A56 drive - NR SignalStrength kept 56 s after the NR leg was released is stale, the tick is LTE`() {
        val result = releasedNr(56_000)
        val nrSs = result.cells.single { it.rat == Rat.NR && it.source == CellSource.SIGNAL_STRENGTH }
        assertFalse(nrSs.servingInferred)
        assertEquals("raw values are kept", -104, nrSs.rsrpDbm)
        assertEquals(56_000L, nrSs.cellInfoAgeMs)
        assertEquals("56000", result.warnings.single { it.code == WarningCode.NR_SIGNAL_STRENGTH_STALE }.rawValue)
        assertFalse(result.nrSignalStrengthPresent)
        val inputs = NetworkClassifier.nsaInputs(result.cells, result.nrSignalStrengthPresent, 0, 13)
        assertEquals(NetworkType.LTE, NetworkClassifier.classify(inputs, result.cells, 0))
        assertTrue("the LTE SignalStrength row is untouched", result.cells.single { it.rat == Rat.LTE && it.source == CellSource.SIGNAL_STRENGTH }.servingInferred)
    }

    @Test
    fun `NR SignalStrength is kept while it can still be current`() {
        fun kept(result: ParseResult) {
            assertTrue(result.cells.single { it.rat == Rat.NR && it.source == CellSource.SIGNAL_STRENGTH }.servingInferred)
            assertTrue(result.nrSignalStrengthPresent)
            assertTrue(result.warnings.none { it.code == WarningCode.NR_SIGNAL_STRENGTH_STALE })
        }
        kept(releasedNr(9_000)) // within 10 s of the release: transition, not stale
        kept(releasedNr(10_000))
        kept(releasedNr(56_000, override = 3)) // the phone still shows 5G
        kept(releasedNr(56_000, override = null)) // override unknown: no basis
        kept(releasedNr(56_000, 0, RawNrCell(false, 2, NOW_MS - 10, Long.MAX_VALUE, 0, 440, 531_870, listOf(7), null, null, nrSignal())))
    }

    @Test
    fun `byte swap helpers`() {
        assertEquals(0x6414, SamsungQuirks.swapBytes(0x1464))
        assertTrue(SamsungQuirks.isByteSwapped(5_220, 25_620))
        assertFalse(SamsungQuirks.isByteSwapped(0x1414, 0x1414))
        assertFalse(SamsungQuirks.isByteSwapped(70_000, 25_620))
    }
}
