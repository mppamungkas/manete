package id.my.pamslab.netlogger.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSummaryTest {
    @Test
    fun `summary names the apps and the MTU`() {
        assertEquals("All apps · MTU 1500 · change in Settings", captureSummary(allApps = true, selectedApps = 3, mtu = 1500))
        assertEquals("1 selected app · MTU 10 000 · change in Settings", captureSummary(allApps = false, selectedApps = 1, mtu = 10_000))
        assertEquals("2 selected apps · MTU 1500 · change in Settings", captureSummary(allApps = false, selectedApps = 2, mtu = 1500))
        assertEquals("All apps (no app selected) · MTU 1500 · change in Settings", captureSummary(allApps = false, selectedApps = 0, mtu = 1500))
    }
}
