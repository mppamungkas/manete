package id.my.pamslab.netlogger.core.data

import id.my.pamslab.netlogger.core.model.device.BatterySample
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.DeviceInfo
import id.my.pamslab.netlogger.core.model.device.TrafficSample
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.BandSource
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.NsaInputs
import id.my.pamslab.netlogger.core.model.radio.ParseWarning
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.radio.WarningCode
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.session.SessionStart

/** Ticks shaped like the moto g45 in NSA (Tri, 2026-10-13). */
object TestData {
    const val START_MS = 1_760_000_000_000L
    const val START_NS = 5_000_000_000_000L

    val device = DeviceInfo("motorola", "moto g45 5G", "15", 35, "M6375_HI434", "0.1.0")

    fun start(uuid: String = "u-1") = SessionStart(uuid, START_MS, START_NS, 1_000, Scenario.STATIC, device)

    val lte = CellSample(
        rat = Rat.LTE, isServing = true, isRegistered = true, connectionStatus = ConnectionStatus.NONE,
        cellId = 12_866_878, tac = 19_350, pci = 479, arfcn = 1475, band = 3, bandSource = BandSource.API,
        bandwidthKhz = 10_000, mcc = "510", mnc = "01", rsrpDbm = -96, rsrqDb = -8, sinrDb = null, rssiDbm = -67,
        cqi = null, timingAdvance = 1, csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null, rscpDbm = null,
        ecNoDb = null, source = CellSource.CELL_INFO, cellInfoAgeMs = 995,
    )

    val nr = lte.copy(
        rat = Rat.NR, isRegistered = false, connectionStatus = ConnectionStatus.SECONDARY_SERVING, cellId = null,
        tac = null, pci = null, arfcn = 531_870, band = 41, bandSource = BandSource.MAPPED, bandwidthKhz = null,
        mcc = null, mnc = null, rsrpDbm = null, rsrqDb = null, rssiDbm = null, timingAdvance = null,
    )

    val nrSignal = nr.copy(
        isRegistered = null, connectionStatus = null, arfcn = null, band = null, bandSource = null,
        rsrpDbm = -100, rsrqDb = -11, sinrDb = 18, source = CellSource.SIGNAL_STRENGTH, cellInfoAgeMs = 2_000,
    )

    val bandWarning = ParseWarning(WarningCode.MULTIPLE_BANDS, Rat.NR, null, "mappedBands", "[7, 41, 90]")
    val tacWarning = ParseWarning(WarningCode.NR_TAC_FROM_LTE, Rat.NR, 479, "tac", "19350")

    fun tick(seq: Long, warnings: List<ParseWarning> = listOf(bandWarning, tacWarning), withFix: Boolean = true) = Tick(
        seq = seq,
        radio = RadioSnapshot(
            wallTimeMs = START_MS + seq * 1_000, elapsedRealtimeNs = START_NS + seq * 1_000_000_000,
            subscriptionId = 1, simSlot = 0, operatorName = "3", operatorNumeric = "51089",
            networkType = NetworkType.NR_NSA, nsaInputs = NsaInputs(true, true, true, 3, 13),
            serviceState = 0, dataState = 2, isRoaming = false, cellBandwidthsKhz = listOf(20_000, 10_000),
            cellInfoOrigin = CellInfoOrigin.FRESH, cells = listOf(lte, nr, nrSignal), warnings = warnings,
            listenerError = null,
        ),
        location = if (withFix) LocationFix(-0.9061, 100.4071, 28.0, 0.3f, 233f, 9.5f, START_NS + seq * 1_000_000_000 - 300_000_000, START_MS, "fused") else null,
        locationStatus = if (withFix) LocationStatus.OK else LocationStatus.WAITING,
        locationAgeMs = if (withFix) 300 else null,
        device = DeviceContext(BatterySample(89, 57_678, 4_354, 33.8f, true), TrafficSample(1, 2, 0, 0), false, 0),
    )
}
