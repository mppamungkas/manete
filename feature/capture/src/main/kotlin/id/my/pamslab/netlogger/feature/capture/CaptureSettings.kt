package id.my.pamslab.netlogger.feature.capture

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.model.capture.CaptureMode
import javax.inject.Inject
import javax.inject.Singleton

/** Capture mode and app filter, chosen on the capture card. */
@Singleton
class CaptureSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    var mode: CaptureMode
        get() = prefs.getString(KEY_MODE, null)?.let { name -> CaptureMode.entries.firstOrNull { it.name == name } } ?: CaptureMode.TCP
        @SuppressLint("UseKtx") set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Packages to capture; empty = every app. */
    var appFilter: List<String>
        get() = prefs.getString(KEY_APPS, "").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        @SuppressLint("UseKtx") set(value) = prefs.edit().putString(KEY_APPS, value.joinToString(",")).apply()

    /**
     * Native capture tun MTU (decision 002, D4): [MTU_REALISTIC] keeps packet sizes realistic; [MTU_FAST] costs much
     * less throughput (zdtun handles one segment per socket per loop round). moto g45, 20 MB downloads at ~138 Mbit/s:
     * −25 % with 1500, −3 % with 10 000 (docs/device-quirks.md).
     */
    var nativeMtu: Int
        get() = prefs.getInt(KEY_MTU, MTU_REALISTIC).takeIf { it == MTU_REALISTIC || it == MTU_FAST } ?: MTU_REALISTIC
        @SuppressLint("UseKtx") set(value) = prefs.edit().putInt(KEY_MTU, value).apply()

    companion object {
        const val MTU_REALISTIC = 1500
        const val MTU_FAST = 10_000
        private const val KEY_MODE = "mode"
        private const val KEY_APPS = "appFilter"
        private const val KEY_MTU = "nativeMtu"
    }
}
