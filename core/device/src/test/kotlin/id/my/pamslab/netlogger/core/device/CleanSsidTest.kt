package id.my.pamslab.netlogger.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanSsidTest {

    @Test
    fun `quotes removed, placeholder and empty names are null`() {
        assertEquals("Lab Wi-Fi", cleanSsid("\"Lab Wi-Fi\""))
        assertEquals("plain", cleanSsid("plain"))
        assertNull(cleanSsid("<unknown ssid>"))
        assertNull(cleanSsid("\"\""))
        assertNull(cleanSsid(""))
    }
}
