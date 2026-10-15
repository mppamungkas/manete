package id.my.pamslab.netlogger.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    @After
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `defaults are all apps and MTU 1500`() {
        val settings = SharedPreferencesCaptureSettings(context)
        assertTrue(settings.allApps.value)
        assertEquals(emptyList<String>(), settings.selectedApps.value)
        assertEquals(1500, settings.nativeMtu.value)
        assertEquals(emptyList<String>(), settings.appFilter)
    }

    @Test
    fun `a package list typed on the old capture card means specific apps`() {
        // Written by feature:capture's CaptureSettings before the move (same file and keys).
        prefs.edit().putString("appFilter", " com.example.video, ,org.example.browser").putInt("nativeMtu", 10_000).commit()
        val settings = SharedPreferencesCaptureSettings(context)
        assertFalse(settings.allApps.value)
        assertEquals(listOf("com.example.video", "org.example.browser"), settings.appFilter)
        assertEquals(10_000, settings.nativeMtu.value)
    }

    @Test
    fun `all apps keeps the selection for later but captures everything`() {
        SharedPreferencesCaptureSettings(context).apply {
            setAllApps(false)
            setSelectedApps(listOf("com.example.video", "com.example.video"))
            setAllApps(true)
        }
        val reread = SharedPreferencesCaptureSettings(context)
        assertTrue(reread.allApps.value)
        assertEquals(listOf("com.example.video"), reread.selectedApps.value)
        assertEquals(emptyList<String>(), reread.appFilter)
        reread.setAllApps(false)
        assertEquals(listOf("com.example.video"), reread.appFilter)
    }

    @Test
    fun `specific apps with none selected captures everything`() {
        val settings = SharedPreferencesCaptureSettings(context)
        settings.setAllApps(false)
        assertEquals(emptyList<String>(), settings.appFilter)
        assertFalse(SharedPreferencesCaptureSettings(context).allApps.value)
    }

    @Test
    fun `an unknown MTU falls back to 1500`() {
        val settings = SharedPreferencesCaptureSettings(context)
        settings.setNativeMtu(9_000)
        assertEquals(1500, settings.nativeMtu.value)
        prefs.edit().putInt("nativeMtu", 576).commit()
        assertEquals(1500, SharedPreferencesCaptureSettings(context).nativeMtu.value)
    }
}
