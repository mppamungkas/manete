package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.model.session.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStyleTest {

    @Test
    fun `user markers stand out, detected events are small rings by kind`() {
        assertEquals("#000000", EventStyle.of("USER_MARKER").fill)
        assertEquals(EventStyle.of("PCI_CHANGE_LTE"), EventStyle.of("PCI_CHANGE_NR"))
        assertEquals(EventStyle.of("RAT_CHANGE").stroke, EventStyle.of("NR_ADDED").stroke)
        assertEquals("#C62828", EventStyle.of("GPS_LOST").stroke)
        EventType.entries.filter { it != EventType.USER_MARKER }.forEach {
            val s = EventStyle.of(it.name)
            assertEquals(it.name, "#FFFFFF", s.fill)
            assertTrue(it.name, s.radius < EventStyle.of("USER_MARKER").radius)
        }
    }
}
