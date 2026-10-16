package id.my.pamslab.netlogger.core.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.model.device.LinkCounters
import id.my.pamslab.netlogger.core.model.device.NetworkRoute
import id.my.pamslab.netlogger.core.model.device.NetworkRoute.Transport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNetworkRouteSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkRouteSource {

    override val route: Flow<NetworkRoute> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(NetworkRoute(Transport.NONE))
            close()
            return@callbackFlow
        }
        // FLAG_INCLUDE_LOCATION_INFO: the SSID is only filled in with it (and the fine location permission).
        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(routeOf(caps))
            }

            override fun onLost(network: Network) {
                trySend(NetworkRoute(Transport.NONE))
            }
        }
        trySend(NetworkRoute(Transport.NONE))
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) { // SecurityException, or too many callbacks
            Log.w(TAG, "default network callback not registered", e)
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    override fun counters() = LinkCounters(
        elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
        mobileRxBytes = DeviceValues.trafficBytes(TrafficStats.getMobileRxBytes()),
        mobileTxBytes = DeviceValues.trafficBytes(TrafficStats.getMobileTxBytes()),
        totalRxBytes = DeviceValues.trafficBytes(TrafficStats.getTotalRxBytes()),
        totalTxBytes = DeviceValues.trafficBytes(TrafficStats.getTotalTxBytes()),
    )

    private fun routeOf(caps: NetworkCapabilities): NetworkRoute {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }
        val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.let(::cleanSsid)
        return NetworkRoute(transport, ssid, vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    private companion object {
        const val TAG = "NetLogger"
    }
}

/** WifiInfo's SSID without quotes; null for the placeholder Android returns when it may not tell. */
internal fun cleanSsid(raw: String): String? =
    raw.takeIf { it.isNotBlank() && it != WifiUnknownSsid }?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }

/** WifiManager.UNKNOWN_SSID. */
private const val WifiUnknownSsid = "<unknown ssid>"
