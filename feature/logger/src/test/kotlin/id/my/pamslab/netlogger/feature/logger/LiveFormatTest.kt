package id.my.pamslab.netlogger.feature.logger

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveFormatTest {

    @Test
    fun `elapsed time as h mm ss`() {
        assertEquals("0:00:00", formatElapsed(0))
        assertEquals("0:00:00", formatElapsed(-5_000_000_000))
        assertEquals("0:01:05", formatElapsed(65_400_000_000))
        assertEquals("2:03:04", formatElapsed((2 * 3600 + 3 * 60 + 4) * 1_000_000_000L))
    }
}
