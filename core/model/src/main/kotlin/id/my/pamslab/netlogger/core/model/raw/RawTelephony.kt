package id.my.pamslab.netlogger.core.model.raw

/*
 * Values copied 1:1 from android.telephony objects by AndroidTelephonySource, so that CellParser can be a
 * pure function tested on the JVM. Nothing here is validated: sentinels such as Int.MAX_VALUE are kept as
 * they came. A null field means the getter threw or the API level does not have it; such failures are
 * counted in `extractionFailures`.
 */

sealed interface RawCell {
    val isRegistered: Boolean?

    /** CellInfo.getCellConnectionStatus(): 0 none, 1 primary serving, 2 secondary serving. */
    val connectionStatus: Int?

    /** CellInfo.getTimestampMillis(), elapsedRealtime base. */
    val timestampMillis: Long?
}

data class RawLteCell(
    override val isRegistered: Boolean?,
    override val connectionStatus: Int?,
    override val timestampMillis: Long?,
    val ci: Int?,
    val tac: Int?,
    val pci: Int?,
    val earfcn: Int?,
    val bands: List<Int>?,
    val bandwidthKhz: Int?,
    val mcc: String?,
    val mnc: String?,
    val signal: RawLteSignal?,
) : RawCell

data class RawLteSignal(
    val rsrp: Int?,
    val rsrq: Int?,
    val rssnr: Int?,
    val rssi: Int?,
    val cqi: Int?,
    val timingAdvance: Int?,
)

data class RawNrCell(
    override val isRegistered: Boolean?,
    override val connectionStatus: Int?,
    override val timestampMillis: Long?,
    val nci: Long?,
    val tac: Int?,
    val pci: Int?,
    val nrarfcn: Int?,
    val bands: List<Int>?,
    val mcc: String?,
    val mnc: String?,
    val signal: RawNrSignal?,
) : RawCell

data class RawNrSignal(
    val ssRsrp: Int?,
    val ssRsrq: Int?,
    val ssSinr: Int?,
    val csiRsrp: Int?,
    val csiRsrq: Int?,
    val csiSinr: Int?,
)

data class RawWcdmaCell(
    override val isRegistered: Boolean?,
    override val connectionStatus: Int?,
    override val timestampMillis: Long?,
    val cid: Int?,
    val lac: Int?,
    val psc: Int?,
    val uarfcn: Int?,
    val mcc: String?,
    val mnc: String?,
    val signal: RawWcdmaSignal?,
) : RawCell

/** `dbm` is CellSignalStrengthWcdma.getDbm(), i.e. RSCP. */
data class RawWcdmaSignal(val dbm: Int?, val ecNo: Int?)

data class RawGsmCell(
    override val isRegistered: Boolean?,
    override val connectionStatus: Int?,
    override val timestampMillis: Long?,
    val cid: Int?,
    val lac: Int?,
    val bsic: Int?,
    val arfcn: Int?,
    val mcc: String?,
    val mnc: String?,
    val signal: RawGsmSignal?,
) : RawCell

data class RawGsmSignal(val rssi: Int?, val timingAdvance: Int?)

enum class CellInfoOrigin {
    /** requestCellInfoUpdate() answered in time. */
    FRESH,

    /** Fell back to getAllCellInfo(), which may return data cached by the modem. */
    CACHED,

    /** No cell info at all (permission missing, modem error, timeout and no cache). */
    UNAVAILABLE,
}

data class RawCellInfoResult(
    val cells: List<RawCell>,
    val origin: CellInfoOrigin,
    /** CellInfoCallback error code, or a negative value for our own failures (see AndroidTelephonySource). */
    val errorCode: Int? = null,
    /** CDMA / TD-SCDMA and unknown CellInfo subclasses, which NetLogger does not record. */
    val unsupportedCells: Int = 0,
    val extractionFailures: Int = 0,
)

data class RawSignalStrength(
    val timestampMillis: Long?,
    val lte: List<RawLteSignal>,
    val nr: List<RawNrSignal>,
    val wcdma: List<RawWcdmaSignal>,
    val gsm: List<RawGsmSignal>,
    val extractionFailures: Int = 0,
)

/** One NetworkRegistrationInfo entry from ServiceState. */
data class RawRegistration(
    val domain: Int?,
    val transportType: Int?,
    val accessNetworkTechnology: Int?,
    val isRegistered: Boolean?,
    val isRoaming: Boolean?,
)

data class RawServiceState(
    val state: Int?,
    val isRoaming: Boolean?,
    val operatorAlphaLong: String?,
    val operatorNumeric: String?,
    val cellBandwidthsKhz: List<Int>?,
    val channelNumber: Int?,
    val registrations: List<RawRegistration>,
    val extractionFailures: Int = 0,
)

data class RawDisplayInfo(val networkType: Int, val overrideNetworkType: Int)

data class RawDataConnection(val state: Int, val networkType: Int)

/** Latest values pushed by TelephonyCallback. Each part is null until its first callback. */
data class RawNetworkState(
    val serviceState: RawServiceState? = null,
    val signalStrength: RawSignalStrength? = null,
    val displayInfo: RawDisplayInfo? = null,
    val dataConnection: RawDataConnection? = null,
    /** Set when the callback could not be registered (e.g. permission missing). */
    val registrationError: String? = null,
)

/** Deprecated TelephonyManager.getCellLocation(); used only to detect the Samsung TAC byte swap. */
data class RawLegacyCellLocation(val cid: Int?, val lac: Int?)

data class RawSubscription(
    val subscriptionId: Int,
    val simSlot: Int?,
    val carrierName: String?,
    val mcc: String?,
    val mnc: String?,
)

data class DeviceTraits(val manufacturer: String, val model: String, val sdkInt: Int) {
    val isSamsung: Boolean get() = manufacturer.equals("samsung", ignoreCase = true)
}
