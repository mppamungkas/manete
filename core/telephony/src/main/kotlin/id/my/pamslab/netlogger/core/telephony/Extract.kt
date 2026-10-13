package id.my.pamslab.netlogger.core.telephony

import android.annotation.SuppressLint
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.util.Log
import id.my.pamslab.netlogger.core.model.raw.RawCell
import id.my.pamslab.netlogger.core.model.raw.RawGsmCell
import id.my.pamslab.netlogger.core.model.raw.RawGsmSignal
import id.my.pamslab.netlogger.core.model.raw.RawLteCell
import id.my.pamslab.netlogger.core.model.raw.RawLteSignal
import id.my.pamslab.netlogger.core.model.raw.RawNrCell
import id.my.pamslab.netlogger.core.model.raw.RawNrSignal
import id.my.pamslab.netlogger.core.model.raw.RawRegistration
import id.my.pamslab.netlogger.core.model.raw.RawServiceState
import id.my.pamslab.netlogger.core.model.raw.RawSignalStrength
import id.my.pamslab.netlogger.core.model.raw.RawWcdmaCell
import id.my.pamslab.netlogger.core.model.raw.RawWcdmaSignal

internal const val TAG = "NetLogger"

/**
 * Copies android.telephony objects into raw DTOs. Every getter runs in its own try/catch because OEM
 * builds throw from places the docs say cannot throw; failures are counted, logged and become null.
 */
internal class Extractor {
    var failures = 0
        private set

    fun <T> get(field: String, block: () -> T): T? = try {
        block()
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        failures++
        Log.w(TAG, "extract $field failed: $e")
        null
    }

    fun cell(info: CellInfo): RawCell? {
        val registered = get("isRegistered") { info.isRegistered }
        val status = get("connectionStatus") { info.cellConnectionStatus }
        val timestamp = get("timestampMillis") { info.timestampMillis }
        return when (info) {
            is CellInfoLte -> {
                val id = get("lte.identity") { info.cellIdentity }
                RawLteCell(
                    isRegistered = registered,
                    connectionStatus = status,
                    timestampMillis = timestamp,
                    ci = get("lte.ci") { id?.ci },
                    tac = get("lte.tac") { id?.tac },
                    pci = get("lte.pci") { id?.pci },
                    earfcn = get("lte.earfcn") { id?.earfcn },
                    bands = get("lte.bands") { id?.bands?.toList() },
                    bandwidthKhz = get("lte.bandwidth") { id?.bandwidth },
                    mcc = get("lte.mcc") { id?.mccString },
                    mnc = get("lte.mnc") { id?.mncString },
                    signal = get("lte.signal") { info.cellSignalStrength }?.let { lteSignal(it) },
                )
            }
            is CellInfoNr -> {
                val id = get("nr.identity") { info.cellIdentity as? CellIdentityNr }
                RawNrCell(
                    isRegistered = registered,
                    connectionStatus = status,
                    timestampMillis = timestamp,
                    nci = get("nr.nci") { id?.nci },
                    tac = get("nr.tac") { id?.tac },
                    pci = get("nr.pci") { id?.pci },
                    nrarfcn = get("nr.nrarfcn") { id?.nrarfcn },
                    bands = get("nr.bands") { id?.bands?.toList() },
                    mcc = get("nr.mcc") { id?.mccString },
                    mnc = get("nr.mnc") { id?.mncString },
                    signal = get("nr.signal") { info.cellSignalStrength as? CellSignalStrengthNr }?.let { nrSignal(it) },
                )
            }
            is CellInfoWcdma -> {
                val id = get("wcdma.identity") { info.cellIdentity }
                RawWcdmaCell(
                    isRegistered = registered,
                    connectionStatus = status,
                    timestampMillis = timestamp,
                    cid = get("wcdma.cid") { id?.cid },
                    lac = get("wcdma.lac") { id?.lac },
                    psc = get("wcdma.psc") { id?.psc },
                    uarfcn = get("wcdma.uarfcn") { id?.uarfcn },
                    mcc = get("wcdma.mcc") { id?.mccString },
                    mnc = get("wcdma.mnc") { id?.mncString },
                    signal = get("wcdma.signal") { info.cellSignalStrength }?.let { wcdmaSignal(it) },
                )
            }
            is CellInfoGsm -> {
                val id = get("gsm.identity") { info.cellIdentity }
                RawGsmCell(
                    isRegistered = registered,
                    connectionStatus = status,
                    timestampMillis = timestamp,
                    cid = get("gsm.cid") { id?.cid },
                    lac = get("gsm.lac") { id?.lac },
                    bsic = get("gsm.bsic") { id?.bsic },
                    arfcn = get("gsm.arfcn") { id?.arfcn },
                    mcc = get("gsm.mcc") { id?.mccString },
                    mnc = get("gsm.mnc") { id?.mncString },
                    signal = get("gsm.signal") { info.cellSignalStrength }?.let { gsmSignal(it) },
                )
            }
            else -> null
        }
    }

    fun lteSignal(s: CellSignalStrengthLte) = RawLteSignal(
        rsrp = get("lte.rsrp") { s.rsrp },
        rsrq = get("lte.rsrq") { s.rsrq },
        rssnr = get("lte.rssnr") { s.rssnr },
        rssi = get("lte.rssi") { s.rssi },
        cqi = get("lte.cqi") { s.cqi },
        timingAdvance = get("lte.ta") { s.timingAdvance },
    )

    fun nrSignal(s: CellSignalStrengthNr) = RawNrSignal(
        ssRsrp = get("nr.ssRsrp") { s.ssRsrp },
        ssRsrq = get("nr.ssRsrq") { s.ssRsrq },
        ssSinr = get("nr.ssSinr") { s.ssSinr },
        csiRsrp = get("nr.csiRsrp") { s.csiRsrp },
        csiRsrq = get("nr.csiRsrq") { s.csiRsrq },
        csiSinr = get("nr.csiSinr") { s.csiSinr },
    )

    fun wcdmaSignal(s: CellSignalStrengthWcdma) = RawWcdmaSignal(
        dbm = get("wcdma.dbm") { s.dbm },
        ecNo = get("wcdma.ecNo") { s.ecNo },
    )

    fun gsmSignal(s: CellSignalStrengthGsm) = RawGsmSignal(
        rssi = get("gsm.rssi") { s.rssi },
        timingAdvance = get("gsm.ta") { s.timingAdvance },
    )

    fun signalStrength(s: SignalStrength): RawSignalStrength {
        val all = get("signalStrength.cells") { s.cellSignalStrengths }.orEmpty()
        return RawSignalStrength(
            timestampMillis = get("signalStrength.timestamp") { s.timestampMillis },
            lte = all.filterIsInstance<CellSignalStrengthLte>().map { lteSignal(it) },
            nr = all.filterIsInstance<CellSignalStrengthNr>().map { nrSignal(it) },
            wcdma = all.filterIsInstance<CellSignalStrengthWcdma>().map { wcdmaSignal(it) },
            gsm = all.filterIsInstance<CellSignalStrengthGsm>().map { gsmSignal(it) },
            extractionFailures = failures,
        )
    }

    // NetworkRegistrationInfo.isRegistered/isRoaming are deprecated in API 35, but their replacement
    // (getNetworkRegistrationState) does not exist on our minSdk 31.
    // Location-gated getters return null on a sanitized ServiceState instead of throwing; the only caller
    // (AndroidTelephonySource.networkState) checks ACCESS_FINE_LOCATION first.
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun serviceState(s: ServiceState): RawServiceState {
        val registrations = get("serviceState.registrations") { s.networkRegistrationInfoList }.orEmpty().map { nri ->
            RawRegistration(
                domain = get("nri.domain") { nri.domain },
                transportType = get("nri.transport") { nri.transportType },
                accessNetworkTechnology = get("nri.ant") { nri.accessNetworkTechnology },
                isRegistered = get("nri.registered") { nri.isRegistered },
                isRoaming = get("nri.roaming") { nri.isRoaming },
            )
        }
        return RawServiceState(
            state = get("serviceState.state") { s.state },
            isRoaming = get("serviceState.roaming") { s.roaming },
            operatorAlphaLong = get("serviceState.operator") { s.operatorAlphaLong },
            operatorNumeric = get("serviceState.numeric") { s.operatorNumeric },
            cellBandwidthsKhz = get("serviceState.bandwidths") { s.cellBandwidths.toList() },
            channelNumber = get("serviceState.channel") { s.channelNumber },
            registrations = registrations,
            extractionFailures = failures,
        )
    }
}
