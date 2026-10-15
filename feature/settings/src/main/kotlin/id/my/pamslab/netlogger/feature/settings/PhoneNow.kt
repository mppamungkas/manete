package id.my.pamslab.netlogger.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address

/** Phone state that decides whether a test works right now. Null = could not be read. */
internal data class PhoneNow(
    /** Null without a mobile data network (no data SIM, or mobile data off). */
    val mobileIpv4: Boolean?,
    val mobileIpv6: Boolean?,
    val wifiConnected: Boolean?,
    val vpnActive: Boolean?,
    val locationOn: Boolean?,
    val locationPermission: Boolean,
    val phonePermission: Boolean,
    val notificationsEnabled: Boolean?,
    val batteryExempt: Boolean?,
)

/** Reads [PhoneNow] with public APIs only (ConnectivityManager, LocationManager, PowerManager). Never throws. */
internal fun readPhoneNow(context: Context): PhoneNow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    // getAllNetworks: the cellular network stays visible under a VPN; the capture does the same (CaptureController).
    @Suppress("DEPRECATION")
    val networks = safe { cm?.allNetworks?.toList() }.orEmpty()
    fun has(transport: Int) = safe { networks.any { cm?.getNetworkCapabilities(it)?.hasTransport(transport) == true } }
    // Internet only: the IMS (VoLTE) network is cellular too, stays up with Wi-Fi on, and often has IPv6 alone (A56).
    val mobile = safe {
        networks.firstOrNull { n ->
            cm?.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
    }
    val addresses = mobile?.let { n -> safe { cm?.getLinkProperties(n)?.linkAddresses?.map { it.address } } }
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    return PhoneNow(
        mobileIpv4 = addresses?.any { it is Inet4Address },
        // Link-local fe80:: alone is no IPv6 connectivity.
        mobileIpv6 = addresses?.any { it is Inet6Address && !it.isLinkLocalAddress },
        wifiConnected = has(NetworkCapabilities.TRANSPORT_WIFI),
        vpnActive = has(NetworkCapabilities.TRANSPORT_VPN),
        locationOn = safe { context.getSystemService(LocationManager::class.java)?.isLocationEnabled },
        locationPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        phonePermission = granted(Manifest.permission.READ_PHONE_STATE),
        // Covers the Android 13+ permission and notifications turned off in the system settings (Android 12 too).
        notificationsEnabled = safe { context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() },
        batteryExempt = safe { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) },
    )
}

/** TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_* as the constant's name. */
internal fun overrideName(override: Int?): String? = when (override) {
    null -> null
    0 -> "NONE"
    1 -> "LTE_CA"
    2 -> "LTE_ADVANCED_PRO"
    3 -> "NR_NSA"
    4 -> "NR_NSA_MMWAVE"
    5 -> "NR_ADVANCED"
    else -> override.toString()
}

private inline fun <T> safe(block: () -> T): T? = try {
    block()
} catch (e: Exception) {
    Log.w("NetLogger", "capabilities: reading phone state failed", e)
    null
}
