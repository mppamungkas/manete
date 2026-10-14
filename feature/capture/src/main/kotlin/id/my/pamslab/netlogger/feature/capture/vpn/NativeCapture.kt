package id.my.pamslab.netlogger.feature.capture.vpn

import android.util.Log

/**
 * libnetcapture, the native part of the VpnService capture (Phase 3b, docs/decisions/002-native-capture.md).
 * Loaded on first use. If it can't be loaded (an ABI we don't build), only native capture is unavailable.
 */
object NativeCapture {

    /** Why the library could not be loaded, or null when it is loaded. */
    val loadError: String? by lazy {
        try {
            System.loadLibrary("netcapture")
            null
        } catch (e: UnsatisfiedLinkError) {
            Log.w("NetLogger", "libnetcapture not loaded", e)
            e.message ?: "libnetcapture not loaded"
        }
    }

    val available: Boolean get() = loadError == null

    /** Library version, ABI and compiler, or null when the library is not loaded. */
    fun version(): String? = if (available) nativeVersion() else null

    @JvmStatic
    private external fun nativeVersion(): String
}
