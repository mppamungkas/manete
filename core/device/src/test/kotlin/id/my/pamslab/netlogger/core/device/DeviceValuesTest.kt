package id.my.pamslab.netlogger.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceValuesTest {

    @Test
    fun `unsupported battery properties are null`() {
        assertNull(DeviceValues.batteryProperty(Int.MIN_VALUE))
        assertNull(DeviceValues.batteryCurrentUa(0))
        assertEquals(-250_000, DeviceValues.batteryCurrentUa(-250_000))
        assertEquals(87, DeviceValues.batteryProperty(87))
    }

    @Test
    fun `traffic counters`() {
        assertNull(DeviceValues.trafficBytes(-1))
        assertEquals(0L, DeviceValues.trafficBytes(0))
        assertEquals(123_456_789_012L, DeviceValues.trafficBytes(123_456_789_012L))
    }

    @Test
    fun `battery temperature voltage and level`() {
        assertEquals(31.5f, DeviceValues.batteryTempC(315))
        assertNull(DeviceValues.batteryTempC(Int.MIN_VALUE))
        assertEquals(4_120, DeviceValues.batteryVoltageMv(4_120))
        assertEquals(4_000, DeviceValues.batteryVoltageMv(4))
        assertNull(DeviceValues.batteryVoltageMv(-1))
        assertEquals(50, DeviceValues.batteryLevelPct(50, 100))
        assertEquals(40, DeviceValues.batteryLevelPct(102, 255))
        assertNull(DeviceValues.batteryLevelPct(-1, 100))
    }
}
