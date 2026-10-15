package id.my.pamslab.netlogger.feature.speedtest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Why a speed test must not run now, or null when it may. */
fun interface NetworkGuard {
    fun problem(): String?
}

/**
 * The test measures the cellular link, so the default network must be mobile data. With Wi-Fi up, Android routes
 * the test over Wi-Fi (and mobile data idles), so the test is refused instead of measuring the wrong link.
 */
class CellularNetworkGuard @Inject constructor(@ApplicationContext private val context: Context) : NetworkGuard {
    override fun problem(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NO_NETWORK
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) null else NOT_CELLULAR
    }

    companion object {
        const val NO_NETWORK = "no network"
        const val NOT_CELLULAR = "default network is not mobile data (Wi-Fi on?)"
    }
}
