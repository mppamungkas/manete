package id.my.pamslab.netlogger.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.raw.DeviceTraits
import id.my.pamslab.netlogger.core.model.raw.RawCellInfoResult
import id.my.pamslab.netlogger.core.model.raw.RawDataConnection
import id.my.pamslab.netlogger.core.model.raw.RawDisplayInfo
import id.my.pamslab.netlogger.core.model.raw.RawLegacyCellLocation
import id.my.pamslab.netlogger.core.model.raw.RawNetworkState
import id.my.pamslab.netlogger.core.model.raw.RawSubscription
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The only class that calls TelephonyManager. Permission checks are explicit; SecurityException is still
 * caught because OEM builds and permission revocation can race with the check.
 */
@Singleton
class AndroidTelephonySource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TelephonySource {

    private val baseManager: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)
    private val subscriptionManager: SubscriptionManager? = context.getSystemService(SubscriptionManager::class.java)

    override fun deviceTraits() = DeviceTraits(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT)

    override fun defaultDataSubscriptionId(): Int? =
        SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    @SuppressLint("MissingPermission") // checked via hasPermission()
    override fun activeDataSubscription(): RawSubscription? {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        val info = if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            Extractor().get("subscriptionInfo") { subscriptionManager?.getActiveSubscriptionInfo(subId) }
        } else {
            null
        }
        return RawSubscription(
            subscriptionId = subId,
            simSlot = info?.simSlotIndex,
            carrierName = info?.carrierName?.toString(),
            mcc = info?.mccString,
            mnc = info?.mncString,
        )
    }

    @SuppressLint("MissingPermission") // checked via hasPermission()
    override fun networkState(subscriptionId: Int?): Flow<RawNetworkState> = callbackFlow {
        val manager = manager(subscriptionId)
        if (manager == null) {
            send(RawNetworkState(registrationError = "TelephonyManager unavailable"))
            awaitClose()
            return@callbackFlow
        }
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE) || !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            send(RawNetworkState(registrationError = "READ_PHONE_STATE or ACCESS_FINE_LOCATION not granted"))
            awaitClose()
            return@callbackFlow
        }

        // One thread, so `state` is only touched sequentially.
        val executor = Executors.newSingleThreadExecutor { Thread(it, "NetLogger-telephony") }
        var state = RawNetworkState(
            serviceState = Extractor().get("serviceState") { manager.serviceState }?.let { Extractor().serviceState(it) },
            signalStrength = Extractor().get("signalStrength") { manager.signalStrength }?.let { Extractor().signalStrength(it) },
        )
        trySend(state)

        fun update(transform: (RawNetworkState) -> RawNetworkState) {
            state = transform(state)
            trySend(state)
        }

        val callback = object :
            TelephonyCallback(),
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DataConnectionStateListener {

            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) = update {
                it.copy(displayInfo = RawDisplayInfo(info.networkType, info.overrideNetworkType))
            }

            override fun onServiceStateChanged(serviceState: ServiceState) = update {
                it.copy(serviceState = Extractor().serviceState(serviceState))
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = update {
                it.copy(signalStrength = Extractor().signalStrength(signalStrength))
            }

            override fun onDataConnectionStateChanged(state: Int, networkType: Int) = update {
                it.copy(dataConnection = RawDataConnection(state, networkType))
            }
        }

        val registered = try {
            manager.registerTelephonyCallback(executor, callback)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "registerTelephonyCallback failed", e)
            trySend(state.copy(registrationError = "SecurityException: ${e.message}"))
            false
        }
        awaitClose {
            if (registered) manager.unregisterTelephonyCallback(callback)
            executor.shutdown()
        }
    }.conflate()

    @SuppressLint("MissingPermission") // checked via hasPermission()
    override suspend fun requestCellInfo(subscriptionId: Int?, timeoutMs: Long): RawCellInfoResult {
        val manager = manager(subscriptionId)
            ?: return RawCellInfoResult(emptyList(), CellInfoOrigin.UNAVAILABLE, ERROR_NO_TELEPHONY)
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return RawCellInfoResult(emptyList(), CellInfoOrigin.UNAVAILABLE, ERROR_NO_PERMISSION)
        }

        var errorCode: Int? = null
        val fresh: List<CellInfo>? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (cont.isActive) cont.resume(cellInfo)
                    }

                    override fun onError(code: Int, detail: Throwable?) {
                        errorCode = code
                        Log.w(TAG, "requestCellInfoUpdate error $code", detail)
                        if (cont.isActive) cont.resume(null)
                    }
                }
                try {
                    manager.requestCellInfoUpdate(DirectExecutor, callback)
                } catch (e: SecurityException) {
                    errorCode = ERROR_NO_PERMISSION
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (fresh == null && errorCode == null) errorCode = ERROR_TIMEOUT

        val origin: CellInfoOrigin
        val list: List<CellInfo>
        if (fresh != null) {
            origin = CellInfoOrigin.FRESH
            list = fresh
        } else {
            val cached = try {
                manager.allCellInfo
            } catch (e: SecurityException) {
                null
            }
            origin = if (cached == null) CellInfoOrigin.UNAVAILABLE else CellInfoOrigin.CACHED
            list = cached.orEmpty()
        }

        val extractor = Extractor()
        var unsupported = 0
        val cells = list.mapNotNull { info -> extractor.cell(info).also { if (it == null) unsupported++ } }
        return RawCellInfoResult(
            cells = cells,
            origin = origin,
            errorCode = errorCode,
            unsupportedCells = unsupported,
            extractionFailures = extractor.failures,
        )
    }

    @SuppressLint("MissingPermission") // checked via hasPermission()
    @Suppress("DEPRECATION")
    override fun legacyCellLocation(subscriptionId: Int?): RawLegacyCellLocation? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val manager = manager(subscriptionId) ?: return null
        val location = Extractor().get("cellLocation") { manager.cellLocation as? android.telephony.gsm.GsmCellLocation } ?: return null
        return RawLegacyCellLocation(cid = location.cid, lac = location.lac)
    }

    private fun manager(subscriptionId: Int?): TelephonyManager? =
        if (subscriptionId == null || subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            baseManager
        } else {
            baseManager?.createForSubscriptionId(subscriptionId)
        }

    private fun hasPermission(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    companion object {
        // Negative so they never collide with CellInfoCallback.ERROR_* codes.
        const val ERROR_TIMEOUT = -1
        const val ERROR_NO_PERMISSION = -2
        const val ERROR_NO_TELEPHONY = -3
    }
}
