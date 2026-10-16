package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationState
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.radio.BandSource
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.NsaInputs
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePreviewTest {
    private val lte = CellSample(
        rat = Rat.LTE, servingInferred = true, isRegistered = true, connectionStatus = ConnectionStatus.PRIMARY_SERVING,
        cellId = 1, tac = 2, pci = 479, arfcn = 1625, band = 3, bandSource = BandSource.API, bandwidthKhz = 20_000,
        mcc = "510", mnc = "89", rsrpDbm = -91, rsrqDb = -8, sinrDb = 14, rssiDbm = -61, cqi = null, timingAdvance = 0,
        csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null, ecNoDb = null,
        source = CellSource.CELL_INFO, cellInfoAgeMs = 900, reportedServing = true,
    )
    private val nrSignal = lte.copy(
        rat = Rat.NR, isRegistered = null, connectionStatus = null, cellId = null, tac = null, pci = null, arfcn = null,
        band = null, bandSource = null, rsrpDbm = -102, rsrqDb = -11, sinrDb = 9, rssiDbm = null, timingAdvance = null,
        source = CellSource.SIGNAL_STRENGTH, reportedServing = null,
    )
    private val radio = RadioSnapshot(
        wallTimeMs = 1_760_000_000_000, elapsedRealtimeNs = 10_000_000_000, subscriptionId = 3, simSlot = 0,
        operatorName = "3", operatorNumeric = "51089", networkType = NetworkType.NR_NSA,
        nsaInputs = NsaInputs(true, false, true, 3, 13), serviceState = 0, dataState = 2, isRoaming = false,
        cellBandwidthsKhz = listOf(20_000), cellInfoOrigin = CellInfoOrigin.FRESH, cells = listOf(lte, nrSignal),
        warnings = emptyList(), listenerError = null,
    )
    private val fix = LocationFix(-0.906, 100.407, 28.0, 0.1f, null, 11f, 9_700_000_000, 1_759_999_999_700, "fused")

    @Test
    fun `a live sample becomes the same point a recorded tick would`() {
        val p = LiveTrail.livePoint(7, radio, LocationState(LocationStatus.OK, fix))
        requireNotNull(p)
        assertEquals(7L, p.seq)
        assertEquals(-0.906, p.lat, 0.0)
        assertEquals("NR_NSA", p.networkType)
        assertEquals(-91, p.lteRsrp)
        assertEquals("CELL_INFO", p.lteRsrpSource)
        assertEquals(-102, p.nrRsrp) // NR only in SignalStrength
        assertEquals("SIGNAL_STRENGTH", p.nrRsrpSource)
        assertEquals(300L, p.locationAgeMs)
    }

    @Test
    fun `no fix, no point`() {
        assertNull(LiveTrail.livePoint(0, radio, LocationState(LocationStatus.WAITING)))
    }

    @Test
    fun `legend counts the points per colour, none included`() {
        val base = requireNotNull(LiveTrail.livePoint(0, radio, LocationState(LocationStatus.OK, fix)))
        val points = listOf(base, base.copy(lteRsrp = -75), base.copy(lteRsrp = -112), base.copy(lteRsrp = null), base.copy(lteRsrp = -90))
        val counts = Metric.LTE_RSRP.legendCounts(points).associate { it.first to it.third }
        assertEquals(1, counts[SignalLevel.GOOD.argb])
        assertEquals(2, counts[SignalLevel.FAIR.argb]) // -91 and -90
        assertEquals(0, counts[SignalLevel.POOR.argb])
        assertEquals(1, counts[SignalLevel.BAD.argb])
        assertEquals(1, counts[SignalLevel.NONE.argb])
        assertEquals(5, Metric.NETWORK_TYPE.legendCounts(points).sumOf { it.third })
    }
}
