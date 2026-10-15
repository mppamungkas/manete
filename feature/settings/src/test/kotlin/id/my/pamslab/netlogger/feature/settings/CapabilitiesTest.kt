package id.my.pamslab.netlogger.feature.settings

import id.my.pamslab.netlogger.core.data.FieldCoverage
import id.my.pamslab.netlogger.core.data.db.RatCoverageRow
import id.my.pamslab.netlogger.core.model.radio.BandSource
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.NsaInputs
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilitiesTest {
    private val lte = CellSample(
        rat = Rat.LTE, servingInferred = true, isRegistered = true, connectionStatus = ConnectionStatus.PRIMARY_SERVING,
        cellId = 12_866_878, tac = 19_350, pci = 479, arfcn = 1475, band = 3, bandSource = BandSource.API,
        bandwidthKhz = 20_000, mcc = "510", mnc = "89", rsrpDbm = -96, rsrqDb = -8, sinrDb = null, rssiDbm = -67,
        cqi = null, timingAdvance = 1, csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null,
        ecNoDb = null, source = CellSource.CELL_INFO, cellInfoAgeMs = 10, reportedServing = true,
    )
    private val nrSignal = lte.copy(
        rat = Rat.NR, isRegistered = null, connectionStatus = null, cellId = null, tac = null, pci = null, arfcn = null,
        band = null, bandSource = null, rsrpDbm = -100, rsrqDb = -11, sinrDb = 18, rssiDbm = null, timingAdvance = null,
        source = CellSource.SIGNAL_STRENGTH,
    )
    private val neighbour = lte.copy(servingInferred = false, isRegistered = false, connectionStatus = ConnectionStatus.NONE, pci = 12)

    private fun snapshot(cells: List<CellSample>, override: Int? = 0, bandwidths: List<Int> = emptyList(), origin: CellInfoOrigin = CellInfoOrigin.FRESH) =
        RadioSnapshot(
            wallTimeMs = 0, elapsedRealtimeNs = 0, subscriptionId = 5, simSlot = 0, operatorName = "3", operatorNumeric = "51089",
            networkType = NetworkType.LTE, nsaInputs = NsaInputs(true, false, false, override, 13), serviceState = 0,
            dataState = 2, isRoaming = false, cellBandwidthsKhz = bandwidths, cellInfoOrigin = origin, cells = cells,
            warnings = emptyList(), listenerError = null,
        )

    private fun List<CapRow>.of(field: CapField) = single { it.field == field }

    @Test
    fun `LTE only - LTE values available or missing, NR fields need coverage`() {
        val rows = evaluateCapabilities(listOf(snapshot(listOf(lte, neighbour), bandwidths = listOf(20_000, 10_000))))
        assertEquals(CapRow(CapField.LTE_RSRP, CapStatus.AVAILABLE, "-96 dBm"), rows.of(CapField.LTE_RSRP))
        assertEquals(CapStatus.MISSING, rows.of(CapField.LTE_SINR).status)
        assertEquals(CapRow(CapField.LTE_BAND, CapStatus.AVAILABLE, "B3 (API)"), rows.of(CapField.LTE_BAND))
        assertEquals(CapStatus.AVAILABLE, rows.of(CapField.LTE_CELL_ID).status)
        CapField.entries.filter { it.group == CapGroup.NR }.forEach { assertEquals(it.name, CapStatus.NEEDS_NR, rows.of(it).status) }
        assertEquals("20 + 10 MHz", rows.of(CapField.CELL_BANDWIDTHS).value)
        assertEquals(CapRow(CapField.NEIGHBOURS, CapStatus.AVAILABLE, "1"), rows.of(CapField.NEIGHBOURS))
        assertEquals(CapRow(CapField.FRESH_CELL_INFO, CapStatus.AVAILABLE, "1 of 1"), rows.of(CapField.FRESH_CELL_INFO))
    }

    @Test
    fun `NSA with NR only in SignalStrength - NR values available, cell list fields missing`() {
        val rows = evaluateCapabilities(
            listOf(
                snapshot(listOf(lte), override = 3, origin = CellInfoOrigin.CACHED),
                snapshot(listOf(lte.copy(band = 3, bandSource = BandSource.MAPPED), nrSignal), override = 3),
            ),
        )
        assertEquals(CapStatus.MISSING, rows.of(CapField.NR_CELL_INFO).status)
        assertEquals(CapStatus.AVAILABLE, rows.of(CapField.NR_SIGNAL_STRENGTH).status)
        assertEquals("-100 dBm", rows.of(CapField.NR_SS_RSRP).value)
        assertEquals("18 dB", rows.of(CapField.NR_SS_SINR).value)
        assertEquals(CapStatus.MISSING, rows.of(CapField.NR_PCI).status)
        assertEquals(CapStatus.MISSING, rows.of(CapField.NR_BAND).status)
        // The latest snapshot's band was mapped.
        assertEquals(CapRow(CapField.LTE_BAND, CapStatus.LIMITED, "B3 (mapped)"), rows.of(CapField.LTE_BAND))
        assertEquals(CapStatus.MISSING, rows.of(CapField.CELL_BANDWIDTHS).status)
        assertEquals(CapStatus.MISSING, rows.of(CapField.NEIGHBOURS).status)
        assertEquals(CapRow(CapField.FRESH_CELL_INFO, CapStatus.LIMITED, "1 of 2"), rows.of(CapField.FRESH_CELL_INFO))
    }

    @Test
    fun `no cell data at all - LTE needs coverage, nothing crashes`() {
        val rows = evaluateCapabilities(listOf(snapshot(emptyList(), override = null, origin = CellInfoOrigin.UNAVAILABLE)))
        assertEquals(CapStatus.NEEDS_LTE, rows.of(CapField.LTE_RSRP).status)
        assertEquals(CapStatus.NEEDS_NR, rows.of(CapField.NR_SS_RSRP).status)
        assertEquals(CapStatus.MISSING, rows.of(CapField.FRESH_CELL_INFO).status)
        assertEquals(CapField.entries.size, evaluateCapabilities(emptyList()).size)
    }

    @Test
    fun `recorded shares use the ticks that had a cell of that kind`() {
        val lteRow = RatCoverageRow(100, 99, 99, 0, 98, 10, 50, 0, 100, 100, 100, 100, 100, 100)
        val c = FieldCoverage(2, 120, lteRow, RatCoverageRow(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, 0, 110, 60, 80)
        assertEquals(Share(99, 100), recordedShare(CapField.LTE_RSRP, c))
        assertNull(recordedShare(CapField.NR_SS_RSRP, c)) // no NR ticks recorded
        assertEquals(Share(110, 120), recordedShare(CapField.CELL_BANDWIDTHS, c))
        assertNull(recordedShare(CapField.FRESH_CELL_INFO, c))
    }

    @Test
    fun `percent never rounds to 0 or 100 unless exact`() {
        assertEquals("100 %", percent(Share(5, 5)))
        assertEquals("99 %", percent(Share(9_999, 10_000)))
        assertEquals("1 %", percent(Share(1, 10_000)))
        assertEquals("0 %", percent(Share(0, 10)))
        assertEquals("50 %", percent(Share(1, 2)))
    }
}
