package id.my.pamslab.netlogger.core.telephony

import id.my.pamslab.netlogger.core.model.device.BatterySample
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.TrafficSample
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.logging.SessionEvent
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.NsaInputs
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.session.EventType
import org.junit.Assert.assertEquals
import org.junit.Test

class EventDetectorTest {

    private var seq = 0L

    private fun lte(pci: Int?, earfcn: Int? = 1625, registered: Boolean = true) = cell(Rat.LTE, pci, earfcn, registered)
    private fun nr(pci: Int?, rsrp: Int = -95) = cell(Rat.NR, pci, 531_870, false, rsrp)

    private fun cell(rat: Rat, pci: Int?, arfcn: Int?, registered: Boolean, rsrp: Int = -95) = CellSample(
        rat = rat, servingInferred = true, isRegistered = registered,
        connectionStatus = if (registered) ConnectionStatus.PRIMARY_SERVING else ConnectionStatus.SECONDARY_SERVING,
        cellId = null, tac = null, pci = pci, arfcn = arfcn, band = null, bandSource = null, bandwidthKhz = null,
        mcc = null, mnc = null, rsrpDbm = rsrp, rsrqDb = null, sinrDb = null, rssiDbm = null, cqi = null, timingAdvance = null,
        csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null, ecNoDb = null,
        source = CellSource.CELL_INFO, cellInfoAgeMs = 0,
    )

    private fun tick(
        vararg cells: CellSample,
        nrSignal: Boolean = false,
        serviceState: Int? = 0,
        gps: LocationStatus = LocationStatus.OK,
        locationAgeMs: Long? = 500,
        dataSubId: Int? = 1,
    ): Tick {
        val nrCell = cells.any { it.rat == Rat.NR }
        val nsa = nrCell || nrSignal
        val n = seq++
        val radio = RadioSnapshot(
            wallTimeMs = T0_MS + n * 1_000, elapsedRealtimeNs = (BOOT_MS + n * 1_000) * 1_000_000, subscriptionId = 1, simSlot = 0,
            operatorName = "3", operatorNumeric = "51089", networkType = if (nsa) NetworkType.NR_NSA else NetworkType.LTE,
            nsaInputs = NsaInputs(true, nrCell, nrSignal, if (nsa) 3 else 0, 13), serviceState = serviceState, dataState = 2,
            isRoaming = false, cellBandwidthsKhz = emptyList(), cellInfoOrigin = CellInfoOrigin.FRESH, cells = cells.toList(),
            warnings = emptyList(), listenerError = null, dataSubscriptionId = dataSubId,
        )
        val fix = if (locationAgeMs == null) null else LocationFix(-0.91 - n * 0.001, 100.46, null, null, null, 4f, 0, 0, "fused")
        return Tick(n, radio, fix, gps, locationAgeMs, DEVICE)
    }

    private fun EventDetector.run(vararg ticks: Tick): List<SessionEvent> = ticks.flatMap(::detect)
    private fun List<SessionEvent>.summary() = map { Triple(it.type, it.fromValue, it.toValue) }

    @Test
    fun `the first tick only sets the state`() {
        assertEquals(emptyList<SessionEvent>(), EventDetector().detect(tick(lte(465), nr(559), serviceState = 1)))
    }

    @Test
    fun `LTE handover - PCI or EARFCN change, a tick without CellInfo is not a change`() {
        val events = EventDetector().run(
            tick(lte(465, 1625)),
            tick(), // no CellInfo this tick
            tick(lte(465, 1625)),
            tick(lte(31, 75)),
            tick(lte(31, 1475)), // same PCI, other carrier
        )
        assertEquals(
            listOf(
                Triple(EventType.PCI_CHANGE_LTE, "465/1625", "31/75"),
                Triple(EventType.PCI_CHANGE_LTE, "31/75", "31/1475"),
            ),
            events.summary(),
        )
    }

    @Test
    fun `the registered LTE cell is the serving one, not a CA secondary listed first`() {
        val events = EventDetector().run(
            tick(lte(200, 75, registered = false), lte(465)),
            tick(lte(201, 75, registered = false), lte(465)),
        )
        assertEquals(emptyList<SessionEvent>(), events)
    }

    @Test
    fun `A56 - NR added, NR cell change, NR removed, re-added on another cell`() {
        val events = EventDetector().run(
            tick(lte(465)),
            tick(lte(465), nr(559)),
            tick(lte(465), nr(559)),
            tick(lte(465), nr(26)),
            tick(lte(465)),
            tick(lte(465), nr(310)),
        )
        assertEquals(
            listOf(
                Triple(EventType.RAT_CHANGE, "LTE", "NR_NSA"),
                Triple(EventType.NR_ADDED, null, "559/531870"),
                Triple(EventType.PCI_CHANGE_NR, "559/531870", "26/531870"),
                Triple(EventType.RAT_CHANGE, "NR_NSA", "LTE"),
                Triple(EventType.NR_REMOVED, "26/531870", null),
                Triple(EventType.RAT_CHANGE, "LTE", "NR_NSA"),
                Triple(EventType.NR_ADDED, null, "310/531870"),
            ),
            events.summary(),
        )
    }

    @Test
    fun `NR serving cell is the strongest of several`() {
        val events = EventDetector().run(
            tick(lte(465), nr(559, rsrp = -90), nr(26, rsrp = -100)),
            tick(lte(465), nr(559, rsrp = -101), nr(26, rsrp = -99)),
        )
        assertEquals(listOf(Triple(EventType.PCI_CHANGE_NR, "559/531870", "26/531870")), events.summary())
    }

    @Test
    fun `moto - NR from SignalStrength only, without a PCI - added and removed, never a PCI change`() {
        val events = EventDetector().run(
            tick(lte(465)),
            tick(lte(465), nrSignal = true),
            tick(lte(465), nr(null), nrSignal = true),
            tick(lte(465)),
        )
        assertEquals(
            listOf(EventType.RAT_CHANGE, EventType.NR_ADDED, EventType.RAT_CHANGE, EventType.NR_REMOVED),
            events.map { it.type },
        )
        assertEquals(listOf(null, null), events.filter { it.type == EventType.NR_ADDED || it.type == EventType.NR_REMOVED }.map { it.fromValue ?: it.toValue })
    }

    @Test
    fun `ping-pong between two cells is kept`() {
        val events = EventDetector().run(tick(lte(50)), tick(lte(31)), tick(lte(50)), tick(lte(31)))
        assertEquals(3, events.count { it.type == EventType.PCI_CHANGE_LTE })
    }

    @Test
    fun `service lost and restored, unknown state in between is ignored`() {
        val events = EventDetector().run(
            tick(lte(465)),
            tick(serviceState = 1),
            tick(serviceState = null),
            tick(lte(465), serviceState = 0),
        )
        assertEquals(
            listOf(Triple(EventType.NO_SERVICE, "0", "1"), Triple(EventType.SERVICE_RESTORED, "1", "0")),
            events.summary(),
        )
    }

    @Test
    fun `a session starting without service reports its restoration`() {
        val events = EventDetector().run(tick(serviceState = 1), tick(lte(465), serviceState = 0))
        assertEquals(listOf(Triple(EventType.SERVICE_RESTORED, "1", "0")), events.summary())
    }

    @Test
    fun `GPS - waiting at the start is no event, then lost when stale or disabled, restored with a fresh fix`() {
        val events = EventDetector().run(
            tick(lte(465), gps = LocationStatus.WAITING, locationAgeMs = null),
            tick(lte(465), locationAgeMs = 800),
            tick(lte(465), locationAgeMs = 5_000),
            tick(lte(465), locationAgeMs = 5_001),
            tick(lte(465), locationAgeMs = 9_000),
            tick(lte(465), locationAgeMs = 300),
            tick(lte(465), gps = LocationStatus.DISABLED, locationAgeMs = 1_300),
            tick(lte(465), locationAgeMs = 200),
        )
        assertEquals(
            listOf(
                Triple(EventType.GPS_LOST, null, EventDetector.STALE_FIX),
                Triple(EventType.GPS_RESTORED, EventDetector.STALE_FIX, null),
                Triple(EventType.GPS_LOST, null, "DISABLED"),
                Triple(EventType.GPS_RESTORED, "DISABLED", null),
            ),
            events.summary(),
        )
    }

    @Test
    fun `data SIM switch and back`() {
        val events = EventDetector().run(tick(lte(465)), tick(lte(465), dataSubId = 5), tick(lte(465), dataSubId = 1))
        assertEquals(
            listOf(Triple(EventType.DATA_SIM_CHANGED, "1", "5"), Triple(EventType.DATA_SIM_CHANGED, "5", "1")),
            events.summary(),
        )
    }

    @Test
    fun `an event carries the tick's times and position`() {
        val detector = EventDetector()
        detector.detect(tick(lte(465)))
        val handover = tick(lte(31, 75))
        val e = detector.detect(handover).single()
        assertEquals(handover.radio.wallTimeMs, e.wallTimeMs)
        assertEquals(handover.radio.elapsedRealtimeNs, e.elapsedRealtimeNs)
        assertEquals(handover.location?.latitude, e.latitude)
        assertEquals(100.46, e.longitude!!, 0.0)
    }

    private companion object {
        const val T0_MS = 1_791_968_546_950L
        const val BOOT_MS = 96_370_107L
        val DEVICE = DeviceContext(BatterySample(80, -300_000, 4_000, 30f, false), TrafficSample(1, 2, 3, 4), false, 0)
    }
}
