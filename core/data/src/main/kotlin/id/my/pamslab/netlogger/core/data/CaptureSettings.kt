package id.my.pamslab.netlogger.core.data

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Traffic capture options, set in Settings (decision 003, D2; moved here from feature:capture so the Settings page
 * can use them). The capture is started and stopped on the Live card.
 */
interface CaptureSettings {
    /** Capture every app (default), or only [selectedApps]. */
    val allApps: StateFlow<Boolean>

    /** Packages chosen under "Specific apps"; kept while [allApps] is on, so switching back restores them. */
    val selectedApps: StateFlow<List<String>>

    /**
     * Tun MTU (decision 002, D4): [MTU_REALISTIC] keeps packet sizes realistic; [MTU_FAST] costs much less
     * throughput (zdtun handles one segment per socket per loop round). moto g45, 20 MB downloads at ~138 Mbit/s:
     * −25 % with 1500, −3 % with 10 000 (docs/device-quirks.md).
     */
    val nativeMtu: StateFlow<Int>

    /** Packages to capture; empty = every app (also "Specific apps" with none selected). */
    val appFilter: List<String> get() = if (allApps.value) emptyList() else selectedApps.value

    fun setAllApps(all: Boolean)

    fun setSelectedApps(packages: List<String>)

    fun setNativeMtu(mtu: Int)

    companion object {
        const val MTU_REALISTIC = 1500
        const val MTU_FAST = 10_000
        val MTUS = listOf(MTU_REALISTIC, MTU_FAST)

        /** [mtu] if it is one of [MTUS], else [MTU_REALISTIC]. */
        fun validMtu(mtu: Int): Int = mtu.takeIf { it in MTUS } ?: MTU_REALISTIC

        /** The stored comma-separated package list. */
        fun parsePackages(text: String?): List<String> =
            text.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}

/**
 * SharedPreferences file "capture", keys `appFilter` and `nativeMtu` as before the move, so earlier choices survive.
 * `allApps` is new; without it, a non-empty `appFilter` (typed on the old capture card) means specific apps.
 */
@Singleton
class SharedPreferencesCaptureSettings @Inject constructor(
    @ApplicationContext context: Context,
) : CaptureSettings {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _selected = MutableStateFlow(CaptureSettings.parsePackages(prefs.getString(KEY_APPS, null)))
    override val selectedApps: StateFlow<List<String>> = _selected.asStateFlow()
    private val _all = MutableStateFlow(prefs.getBoolean(KEY_ALL, _selected.value.isEmpty()))
    override val allApps: StateFlow<Boolean> = _all.asStateFlow()
    private val _mtu = MutableStateFlow(CaptureSettings.validMtu(prefs.getInt(KEY_MTU, CaptureSettings.MTU_REALISTIC)))
    override val nativeMtu: StateFlow<Int> = _mtu.asStateFlow()

    @SuppressLint("UseKtx") // core-ktx is not a dependency of core:data; not worth one for a single edit.
    override fun setAllApps(all: Boolean) {
        prefs.edit().putBoolean(KEY_ALL, all).apply()
        _all.value = all
    }

    @SuppressLint("UseKtx")
    override fun setSelectedApps(packages: List<String>) {
        val clean = CaptureSettings.parsePackages(packages.joinToString(","))
        prefs.edit().putString(KEY_APPS, clean.joinToString(",")).apply()
        _selected.value = clean
    }

    @SuppressLint("UseKtx")
    override fun setNativeMtu(mtu: Int) {
        val valid = CaptureSettings.validMtu(mtu)
        prefs.edit().putInt(KEY_MTU, valid).apply()
        _mtu.value = valid
    }

    private companion object {
        const val FILE = "capture"
        const val KEY_APPS = "appFilter"
        const val KEY_ALL = "allApps"
        const val KEY_MTU = "nativeMtu"
    }
}
