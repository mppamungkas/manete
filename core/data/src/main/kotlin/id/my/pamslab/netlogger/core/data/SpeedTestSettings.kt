package id.my.pamslab.netlogger.core.data

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.model.speedtest.PeriodicSpeedTest
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestTarget
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speed test server URLs, set in Settings. Empty until the user enters a server: nothing points at a particular
 * server by default. The public Cloudflare test ([SpeedTestConfig.cloudflare]) needs no setting: Live always offers it
 * as its own button, never as the default (2026-10-17; the earlier on/off switch hid it behind a grey link).
 */
interface SpeedTestSettings {
    val config: StateFlow<SpeedTestConfig>

    fun save(pingUrl: String, downloadUrl: String, uploadUrl: String)

    /** The speed test repeated during a session; off by default. A running session keeps the one it started with. */
    val periodic: StateFlow<PeriodicSpeedTest>

    fun savePeriodic(periodic: PeriodicSpeedTest)
}

/** SharedPreferences (no extra dependency for a few values). */
@Singleton
class SharedPreferencesSpeedTestSettings @Inject constructor(
    @ApplicationContext context: Context,
) : SpeedTestSettings {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    override val config: StateFlow<SpeedTestConfig> = _config.asStateFlow()
    private val _periodic = MutableStateFlow(readPeriodic())
    override val periodic: StateFlow<PeriodicSpeedTest> = _periodic.asStateFlow()

    @SuppressLint("UseKtx") // core-ktx is not a dependency of core:data; not worth one for a single edit.
    override fun save(pingUrl: String, downloadUrl: String, uploadUrl: String) {
        prefs.edit()
            .putString(KEY_PING, pingUrl.trim())
            .putString(KEY_DOWNLOAD, downloadUrl.trim())
            .putString(KEY_UPLOAD, uploadUrl.trim())
            .apply()
        _config.value = read()
    }

    @SuppressLint("UseKtx")
    override fun savePeriodic(periodic: PeriodicSpeedTest) {
        prefs.edit()
            .putInt(KEY_INTERVAL, periodic.intervalMin)
            .putString(KEY_TARGET, periodic.target.name)
            .putStringSet(KEY_PHASES, periodic.phases.map { it.name }.toSet())
            .putLong(KEY_DURATION, periodic.durationMs)
            .putLong(KEY_MAX_DOWN, periodic.maxDownloadBytes)
            .putLong(KEY_MAX_UP, periodic.maxUploadBytes)
            .apply()
        _periodic.value = readPeriodic()
    }

    /** Unknown or out-of-list values (e.g. from a later version) fall back to the defaults. */
    private fun readPeriodic(): PeriodicSpeedTest {
        val default = PeriodicSpeedTest()
        return PeriodicSpeedTest(
            intervalMin = prefs.getInt(KEY_INTERVAL, 0).takeIf { it in PeriodicSpeedTest.INTERVALS_MIN } ?: 0,
            target = SpeedTestTarget.entries.firstOrNull { it.name == prefs.getString(KEY_TARGET, null) } ?: default.target,
            phases = prefs.getStringSet(KEY_PHASES, null)
                ?.mapNotNull { name -> ThroughputType.entries.firstOrNull { it.name == name } }?.toSet()
                ?: default.phases,
            durationMs = prefs.getLong(KEY_DURATION, default.durationMs).takeIf { it in PeriodicSpeedTest.DURATIONS_MS } ?: default.durationMs,
            maxDownloadBytes = prefs.getLong(KEY_MAX_DOWN, default.maxDownloadBytes)
                .takeIf { it in PeriodicSpeedTest.DOWNLOAD_CAPS } ?: default.maxDownloadBytes,
            maxUploadBytes = prefs.getLong(KEY_MAX_UP, default.maxUploadBytes)
                .takeIf { it in PeriodicSpeedTest.UPLOAD_CAPS } ?: default.maxUploadBytes,
        )
    }

    private fun read() = SpeedTestConfig(
        pingUrl = prefs.getString(KEY_PING, null).orEmpty(),
        downloadUrl = prefs.getString(KEY_DOWNLOAD, null).orEmpty(),
        uploadUrl = prefs.getString(KEY_UPLOAD, null).orEmpty(),
    )

    private companion object {
        const val FILE = "speedtest"
        const val KEY_PING = "pingUrl"
        const val KEY_DOWNLOAD = "downloadUrl"
        const val KEY_UPLOAD = "uploadUrl"
        const val KEY_INTERVAL = "periodicIntervalMin"
        const val KEY_TARGET = "periodicTarget"
        const val KEY_PHASES = "periodicPhases"
        const val KEY_DURATION = "periodicDurationMs"
        const val KEY_MAX_DOWN = "periodicMaxDownloadBytes"
        const val KEY_MAX_UP = "periodicMaxUploadBytes"
    }
}
