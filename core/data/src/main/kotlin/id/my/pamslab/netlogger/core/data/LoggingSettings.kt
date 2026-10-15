package id.my.pamslab.netlogger.core.data

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.model.session.Scenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Options for the next logging session, set in Settings (decision 003, D4). */
interface LoggingSettings {
    /** Tick interval of the next session; one of [INTERVALS_MS]. */
    val intervalMs: StateFlow<Long>

    /** Scenario preselected on Live; null = the last session's. */
    val defaultScenario: StateFlow<Scenario?>

    fun setIntervalMs(intervalMs: Long)

    fun setDefaultScenario(scenario: Scenario?)

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
        val INTERVALS_MS = listOf(500L, 1_000L, 2_000L, 5_000L)

        /** [intervalMs] if it is one of [INTERVALS_MS], else [DEFAULT_INTERVAL_MS]. */
        fun validInterval(intervalMs: Long): Long = intervalMs.takeIf { it in INTERVALS_MS } ?: DEFAULT_INTERVAL_MS

        /** The scenario stored as [name]; null for no or an unknown name. */
        fun scenarioOf(name: String?): Scenario? = Scenario.entries.firstOrNull { it.name == name }
    }
}

/** SharedPreferences, like [SharedPreferencesSpeedTestSettings]. */
@Singleton
class SharedPreferencesLoggingSettings @Inject constructor(
    @ApplicationContext context: Context,
) : LoggingSettings {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _interval = MutableStateFlow(LoggingSettings.validInterval(prefs.getLong(KEY_INTERVAL, LoggingSettings.DEFAULT_INTERVAL_MS)))
    override val intervalMs: StateFlow<Long> = _interval.asStateFlow()
    private val _scenario = MutableStateFlow(LoggingSettings.scenarioOf(prefs.getString(KEY_SCENARIO, null)))
    override val defaultScenario: StateFlow<Scenario?> = _scenario.asStateFlow()

    @SuppressLint("UseKtx") // core-ktx is not a dependency of core:data; not worth one for a single edit.
    override fun setIntervalMs(intervalMs: Long) {
        val valid = LoggingSettings.validInterval(intervalMs)
        prefs.edit().putLong(KEY_INTERVAL, valid).apply()
        _interval.value = valid
    }

    @SuppressLint("UseKtx")
    override fun setDefaultScenario(scenario: Scenario?) {
        prefs.edit().putString(KEY_SCENARIO, scenario?.name).apply()
        _scenario.value = scenario
    }

    private companion object {
        const val FILE = "logging"
        const val KEY_INTERVAL = "intervalMs"
        const val KEY_SCENARIO = "defaultScenario"
    }
}
