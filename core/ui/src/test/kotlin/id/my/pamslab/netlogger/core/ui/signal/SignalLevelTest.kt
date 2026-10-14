package id.my.pamslab.netlogger.core.ui.signal

import id.my.pamslab.netlogger.core.ui.signal.SignalLevel.BAD
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel.FAIR
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel.GOOD
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel.NONE
import id.my.pamslab.netlogger.core.ui.signal.SignalLevel.POOR
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalLevelTest {

    @Test
    fun `RSRP classes and boundaries`() {
        mapOf(-44 to GOOD, -79 to GOOD, -80 to FAIR, -95 to FAIR, -96 to POOR, -110 to POOR, -111 to BAD, -140 to BAD)
            .forEach { (dbm, level) -> assertEquals("$dbm", level, SignalLevel.rsrp(dbm)) }
        assertEquals(NONE, SignalLevel.rsrp(null))
    }

    @Test
    fun `SINR classes and boundaries`() {
        mapOf(30 to GOOD, 20 to GOOD, 19 to FAIR, 13 to FAIR, 12 to POOR, 0 to POOR, -1 to BAD, -23 to BAD)
            .forEach { (db, level) -> assertEquals("$db", level, SignalLevel.sinr(db)) }
        assertEquals(NONE, SignalLevel.sinr(null))
    }

    @Test
    fun `hex for the map renderer`() {
        assertEquals("#2E7D32", GOOD.hex)
        assertEquals("#9E9E9E", NONE.hex)
    }
}
