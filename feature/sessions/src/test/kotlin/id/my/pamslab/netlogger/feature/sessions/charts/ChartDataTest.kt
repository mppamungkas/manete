package id.my.pamslab.netlogger.feature.sessions.charts

import id.my.pamslab.netlogger.core.data.db.CellEntity
import id.my.pamslab.netlogger.core.data.db.MeasurementEntity
import id.my.pamslab.netlogger.core.data.db.ThroughputTestEntity
import id.my.pamslab.netlogger.core.export.FlatRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartDataTest {

    private fun tick(seq: Long, networkType: String = "LTE", screenOn: Boolean? = true, atSec: Long = seq) = MeasurementEntity(
        id = seq + 1, sessionId = 1, seq = seq, wallTimeMs = T0 + atSec * 1_000, elapsedRealtimeNs = BOOT_NS + atSec * 1_000_000_000,
        lat = null, lon = null, altitude = null, speedMps = null, bearing = null, gpsAccuracy = null, locationAgeMs = null,
        locationStatus = "OK", networkType = networkType, overrideNetworkType = 0, dataNetworkType = 13, nsaLteServing = true,
        nsaNrCellInfo = false, nsaNrSignal = false, serviceState = 0, dataState = 2, isRoaming = false, operatorNumeric = "51089",
        cellInfoOrigin = "FRESH", cellBandwidthsJson = "[]", batteryLevel = null, batteryCurrentUa = null, batteryVoltageMv = null,
        batteryTempC = null, isCharging = null, rxBytesTotal = null, txBytesTotal = null, rxBytesApp = null, txBytesApp = null,
        screenOn = screenOn, thermalStatus = null, parseErrors = 0, warningCount = 0,
    )

    private fun cell(rat: String, source: String = "CELL_INFO", serving: Boolean = true, rsrp: Int? = null, sinr: Int? = null) = CellEntity(
        measurementId = 0, rat = rat, servingInferred = serving, reportedServing = if (source == "CELL_INFO") serving else null,
        servingSource = null, isRegistered = rat == "LTE" && source == "CELL_INFO", connectionStatus = null, ciOrNci = null,
        tac = null, pci = 1, arfcn = null, band = null, bandSource = null, bandwidthKhz = null, mcc = null, mnc = null,
        rsrpDbm = rsrp, rsrqDb = null, sinrDb = sinr, rssiDbm = null, cqi = null, timingAdvance = null, csiRsrpDbm = null,
        csiRsrqDb = null, csiSinrDb = null, rscpDbm = null, ecNoDb = null, source = source, cellInfoAgeMs = 100,
    )

    private fun data(vararg rows: FlatRow, tests: List<ThroughputTestEntity> = emptyList()) = ChartData.of(rows.toList(), 1_000, tests)

    @Test
    fun `values follow flat_csv - CellInfo first, SignalStrength fills in, a stale entry does not`() {
        val d = data(
            FlatRow.of(tick(0), listOf(cell("LTE", rsrp = -95), cell("LTE", "SIGNAL_STRENGTH", rsrp = -96, sinr = 7))),
            FlatRow.of(tick(1), listOf(cell("LTE", rsrp = -97), cell("LTE", "SIGNAL_STRENGTH", serving = false, sinr = 9))),
        )
        assertEquals(-95, d.ticks[0].lteRsrp); assertEquals("CELL_INFO", d.ticks[0].lteRsrpSource)
        assertEquals(7, d.ticks[0].lteSinr); assertEquals("SIGNAL_STRENGTH", d.ticks[0].lteSinrSource)
        assertNull("stale LTE SignalStrength (moto, screen off)", d.ticks[1].lteSinr)
        assertEquals(1.0, d.ticks[1].tSec, 1e-9)
    }

    @Test
    fun `lines break at missing values and at missed ticks, never interpolate`() {
        val d = data(
            FlatRow.of(tick(0), listOf(cell("LTE", rsrp = -90))),
            FlatRow.of(tick(1), listOf(cell("LTE", rsrp = -91))),
            FlatRow.of(tick(2), listOf(cell("LTE"))), // no value
            FlatRow.of(tick(3), listOf(cell("LTE", rsrp = -93))),
            FlatRow.of(tick(4, atSec = 4), listOf(cell("LTE", rsrp = -94))),
            FlatRow.of(tick(5, atSec = 9), listOf(cell("LTE", rsrp = -99))), // 5 s gap: missed ticks
        )
        assertEquals(
            listOf(listOf(0.0 to -90, 1.0 to -91), listOf(3.0 to -93, 4.0 to -94), listOf(9.0 to -99)),
            d.lines { it.lteRsrp },
        )
    }

    @Test
    fun `network type and screen-off segments`() {
        val d = data(
            FlatRow.of(tick(0, "LTE"), emptyList()),
            FlatRow.of(tick(1, "NR_NSA", screenOn = false), emptyList()),
            FlatRow.of(tick(2, "NR_NSA", screenOn = false), emptyList()),
            FlatRow.of(tick(3, "LTE", screenOn = null), emptyList()),
        )
        assertEquals(
            listOf(Segment(0.0, 1.0, "LTE"), Segment(1.0, 3.0, "NR_NSA"), Segment(3.0, 4.0, "LTE")),
            d.networkTypes,
        )
        assertEquals("unknown screen state is not drawn as off", listOf(Segment(1.0, 3.0, true)), d.screenOff)
    }

    @Test
    fun `nearest tick, speed tests on the time axis`() {
        val test = ThroughputTestEntity(
            sessionId = 1, startedAtMs = T0 + 2_000, startedElapsedNs = BOOT_NS + 2_500_000_000, type = "DOWNLOAD",
            serverUrl = "u", durationMs = 10_000, bytes = 1, mbps = 40.0, streams = 4, rttMinMs = null, rttAvgMs = null,
            rttMaxMs = null, jitterMs = null, lossPct = null, captureActive = false, error = null,
        )
        val d = data(*(0L..4).map { FlatRow.of(tick(it), emptyList()) }.toTypedArray(), tests = listOf(test))
        assertEquals(0, d.nearest(-3.0)); assertEquals(2, d.nearest(2.4)); assertEquals(3, d.nearest(2.6)); assertEquals(4, d.nearest(99.0))
        assertNull(ChartData(emptyList(), 1_000).nearest(1.0))
        assertEquals(ChartTest(2.5, 12.5, "DOWNLOAD", 40.0, null), d.tests.single())
    }

    @Test
    fun `axis covers the data and at least the default range, rounded to the step`() {
        assertEquals(AxisRange(-120.0, -70.0, 10.0), AxisRange.of(listOf(-100, -85), -120.0..-70.0, 10.0))
        assertEquals(AxisRange(-140.0, -60.0, 10.0), AxisRange.of(listOf(-131, -62), -120.0..-70.0, 10.0))
        assertEquals(AxisRange(-5.0, 25.0, 5.0), AxisRange.of(emptyList(), -5.0..25.0, 5.0))
    }

    @Test
    fun `readout marks SignalStrength values and screen off`() {
        val t = ChartTick(12, 12.0, T0, "NR_NSA", false, -95, "CELL_INFO", -88, "SIGNAL_STRENGTH", null, null, 9, "SIGNAL_STRENGTH")
        assertEquals(
            "16:05:12 #12 NR_NSA screen off\nRSRP LTE -95 · NR -88 (SS)\nSINR LTE – · NR 9 (SS)",
            readout(t) { "16:05:12" },
        )
    }

    private companion object {
        const val T0 = 1_791_968_546_950L
        const val BOOT_NS = 96_370_107_445_262L
    }
}
