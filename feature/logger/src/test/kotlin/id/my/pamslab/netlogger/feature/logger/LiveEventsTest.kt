package id.my.pamslab.netlogger.feature.logger

import id.my.pamslab.netlogger.core.model.device.BatterySample
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.TrafficSample
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.NsaInputs
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.session.EventType
import id.my.pamslab.netlogger.core.telephony.EventDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEventsTest {

    @Test
    fun `LTE cell change carries the RSRP before and after`() {
        val detector = EventDetector()
        val tracker = LiveEventTracker()
        listOf(tick(0, lte(123, -104)), tick(1, lte(123, -105)), tick(2, lte(211, -93))).forEach { tracker.add(it, detector.detect(it)) }

        val e = tracker.events.single()
        assertEquals(EventType.PCI_CHANGE_LTE, e.type)
        assertEquals(-105, e.levelBeforeDbm)
        assertEquals(-93, e.levelAfterDbm)
        assertEquals("LTE cell change", e.type.title())
        assertEquals("PCI 123 → 211   -105 → -93 dBm", e.detail())
    }

    @Test
    fun `NR cell change is confirmed a tick late but compares with the tick before it started`() {
        val detector = EventDetector()
        val tracker = LiveEventTracker()
        listOf(
            tick(0, lte(1, -90), nr(440, -100)),
            tick(1, lte(1, -90), nr(440, -101)),
            tick(2, lte(1, -90), nr(161, -95)), // candidate
            tick(3, lte(1, -90), nr(161, -94)), // confirmed: event dated tick 2
        ).forEach { tracker.add(it, detector.detect(it)) }

        val e = tracker.events.single()
        assertEquals(EventType.PCI_CHANGE_NR, e.type)
        assertEquals(-101, e.levelBeforeDbm)
        assertEquals(-94, e.levelAfterDbm)
    }

    @Test
    fun `keeps the newest five, newest first, and words for other events`() {
        val detector = EventDetector()
        val tracker = LiveEventTracker()
        (0..7).forEach { i -> tick(i.toLong(), lte(100 + i, -90)).let { tracker.add(it, detector.detect(it)) } }
        assertEquals(listOf("PCI 106 → 107", "PCI 105 → 106"), tracker.events.take(2).map { it.detail().substringBefore("   ") })
        assertEquals(5, tracker.events.size)

        assertEquals("LTE → 5G NSA", LiveEvent(EventType.RAT_CHANGE, 0, "LTE", "NR_NSA").detail())
        assertEquals("no fix", LiveEvent(EventType.GPS_LOST, 0, null, EventDetector.NO_FIX).detail())
        assertEquals("123/1625 → 211/1850", LiveEvent(EventType.PCI_CHANGE_LTE, 0, "123/1625", "211/1850").detail())
        assertTrue(LiveEvent(EventType.USER_MARKER, 0, null, null).detail().isEmpty())
    }

    private fun tick(i: Long, vararg cells: CellSample) = Tick(
        seq = i,
        radio = RadioSnapshot(
            wallTimeMs = i * 1_000, elapsedRealtimeNs = i * 1_000_000_000, subscriptionId = 1, simSlot = 0, operatorName = "3",
            operatorNumeric = "51089", networkType = NetworkType.LTE, nsaInputs = NsaInputs(true, cells.any { it.rat == Rat.NR }, false, 0, 13),
            serviceState = 0, dataState = 2, isRoaming = false, cellBandwidthsKhz = emptyList(),
            cellInfoOrigin = CellInfoOrigin.FRESH, cells = cells.toList(), warnings = emptyList(), listenerError = null,
        ),
        location = null,
        locationStatus = LocationStatus.WAITING,
        locationAgeMs = null,
        device = DeviceContext(BatterySample(80, null, null, null, false), TrafficSample(null, null, null, null), true, 0),
    )

    private fun lte(pci: Int, rsrp: Int) = cell(Rat.LTE, pci, 1625, rsrp, ConnectionStatus.PRIMARY_SERVING)

    private fun nr(pci: Int, rsrp: Int) = cell(Rat.NR, pci, 531_870, rsrp, ConnectionStatus.SECONDARY_SERVING)

    private fun cell(rat: Rat, pci: Int, arfcn: Int, rsrp: Int, status: ConnectionStatus) = CellSample(
        rat = rat, servingInferred = true, isRegistered = rat == Rat.LTE, connectionStatus = status, cellId = null, tac = null,
        pci = pci, arfcn = arfcn, band = null, bandSource = null, bandwidthKhz = null, mcc = null, mnc = null,
        rsrpDbm = rsrp, rsrqDb = null, sinrDb = null, rssiDbm = null, cqi = null, timingAdvance = null,
        csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null, ecNoDb = null,
        source = CellSource.CELL_INFO, cellInfoAgeMs = 100,
    )
}
