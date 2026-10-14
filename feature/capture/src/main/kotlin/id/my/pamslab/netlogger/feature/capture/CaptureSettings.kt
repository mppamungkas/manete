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

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_APPS = "appFilter"
    }
}
