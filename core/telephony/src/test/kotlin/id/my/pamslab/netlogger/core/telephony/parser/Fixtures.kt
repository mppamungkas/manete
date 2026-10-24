package id.my.pamslab.netlogger.core.telephony.parser

import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.raw.DeviceTraits
import id.my.pamslab.netlogger.core.model.raw.RawCell
import id.my.pamslab.netlogger.core.model.raw.RawCellInfoResult
import id.my.pamslab.netlogger.core.model.raw.RawGsmCell
import id.my.pamslab.netlogger.core.model.raw.RawGsmSignal
import id.my.pamslab.netlogger.core.model.raw.RawLteCell
import id.my.pamslab.netlogger.core.model.raw.RawLteSignal
import id.my.pamslab.netlogger.core.model.raw.RawNrCell
import id.my.pamslab.netlogger.core.model.raw.RawNrSignal
import id.my.pamslab.netlogger.core.model.raw.RawSignalStrength
import id.my.pamslab.netlogger.core.model.raw.RawWcdmaCell
import id.my.pamslab.netlogger.core.model.raw.RawWcdmaSignal

const val UNAVAILABLE = Int.MAX_VALUE
const val NOW_MS = 1_000_000L

val MOTO = DeviceTraits("motorola", "moto g45 5G", 35)
val SAMSUNG = DeviceTraits("samsung", "SM-A566B", 36)
val XIAOMI = DeviceTraits("Xiaomi", "24094RAD4G", 36)

fun lteSignal(rsrp: Int? = -95, rsrq: Int? = -11, rssnr: Int? = 12, rssi: Int? = -65, cqi: Int? = 9, ta: Int? = 3) =
    RawLteSignal(rsrp, rsrq, rssnr, rssi, cqi, ta)

fun nrSignal(ssRsrp: Int? = -100, ssRsrq: Int? = -12, ssSinr: Int? = 8) =
    RawNrSignal(ssRsrp, ssRsrq, ssSinr, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE)

fun lteCell(
    registered: Boolean = true,
    status: Int = if (registered) 1 else 0,
    timestamp: Long? = NOW_MS - 200,
    ci: Int? = 12_345_678,
    tac: Int? = 25_620,
    pci: Int? = 101,
    earfcn: Int? = 1850,
    bands: List<Int>? = listOf(3),
    bandwidthKhz: Int? = 20_000,
    signal: RawLteSignal? = lteSignal(),
) = RawLteCell(registered, status, timestamp, ci, tac, pci, earfcn, bands, bandwidthKhz, "510", "10", signal)

fun nrCell(
    registered: Boolean = false,
    status: Int = 2,
    nci: Long? = Long.MAX_VALUE,
    pci: Int? = 500,
    arfcn: Int? = 470_000,
    bands: List<Int>? = listOf(40),
    signal: RawNrSignal? = nrSignal(),
) = RawNrCell(registered, status, NOW_MS - 100, nci, UNAVAILABLE, pci, arfcn, bands, null, null, signal)

fun wcdmaCell(registered: Boolean = false, ecNo: Int? = -8) =
    RawWcdmaCell(registered, if (registered) 1 else 0, NOW_MS, 1234, 100, 200, 10_788, "510", "10", RawWcdmaSignal(-85, ecNo))

fun gsmCell(registered: Boolean = false, rssi: Int? = -70, ta: Int? = 2) =
    RawGsmCell(registered, if (registered) 1 else 0, NOW_MS, 4321, 100, 30, 50, "510", "10", RawGsmSignal(rssi, ta))

fun cellInfo(vararg cells: RawCell, origin: CellInfoOrigin = CellInfoOrigin.FRESH) =
    RawCellInfoResult(cells.toList(), origin)

fun signalStrength(lte: List<RawLteSignal> = emptyList(), nr: List<RawNrSignal> = emptyList(), timestamp: Long? = NOW_MS - 50) =
    RawSignalStrength(timestamp, lte, nr, emptyList(), emptyList())
