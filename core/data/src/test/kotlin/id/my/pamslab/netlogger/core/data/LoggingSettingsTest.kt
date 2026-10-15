package id.my.pamslab.netlogger.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.my.pamslab.netlogger.core.model.session.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoggingSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `defaults are 1000 ms and the last session's scenario`() {
        val settings = SharedPreferencesLoggingSettings(context)
        assertEquals(1_000L, settings.intervalMs.value)
        assertNull(settings.defaultScenario.value)
    }

    @Test
    fun `choices survive a new instance`() {
        SharedPreferencesLoggingSettings(context).apply {
            setIntervalMs(2_000L)
            setDefaultScenario(Scenario.WALK)
        }
        val reread = SharedPreferencesLoggingSettings(context)
        assertEquals(2_000L, reread.intervalMs.value)
        assertEquals(Scenario.WALK, reread.defaultScenario.value)

        reread.setDefaultScenario(null)
        assertNull(SharedPreferencesLoggingSettings(context).defaultScenario.value)
    }

    @Test
    fun `an interval outside the choices falls back to 1000 ms`() {
        val settings = SharedPreferencesLoggingSettings(context)
        settings.setIntervalMs(750L)
        assertEquals(1_000L, settings.intervalMs.value)
        context.getSharedPreferences("logging", Context.MODE_PRIVATE).edit().putLong("intervalMs", 0L).commit()
        assertEquals(1_000L, SharedPreferencesLoggingSettings(context).intervalMs.value)
    }

    @Test
    fun `an unknown stored scenario reads as none`() {
        assertNull(LoggingSettings.scenarioOf("BIKE"))
        assertNull(LoggingSettings.scenarioOf(null))
        assertEquals(Scenario.INDOOR, LoggingSettings.scenarioOf("INDOOR"))
    }
}
