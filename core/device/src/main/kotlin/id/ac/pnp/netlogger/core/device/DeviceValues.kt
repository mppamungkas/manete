package id.ac.pnp.netlogger.core.device

/** Sentinel handling for platform values, kept free of Android types so it is unit-testable. */
internal object DeviceValues {

    /** TrafficStats.UNSUPPORTED. */
    private const val TRAFFIC_UNSUPPORTED = -1L

    /** BatteryManager.getIntProperty() returns Int.MIN_VALUE (older: 0) when the property is unsupported. */
    fun batteryProperty(raw: Int): Int? = raw.takeIf { it != Int.MIN_VALUE && it != Int.MAX_VALUE }

    /** BatteryManager.getIntProperty(CURRENT_NOW): 0 also means "not supported" on many devices. */
    fun batteryCurrentUa(raw: Int): Int? = batteryProperty(raw)?.takeIf { it != 0 }

    fun trafficBytes(raw: Long): Long? = raw.takeIf { it != TRAFFIC_UNSUPPORTED && it >= 0 }

    /** EXTRA_TEMPERATURE is in tenths of °C; -1 (extra missing) and implausible values become null. */
    fun batteryTempC(tenths: Int): Float? = tenths.takeIf { it in -400..1000 }?.let { it / 10f }

    /** EXTRA_VOLTAGE in mV; some devices report volts (e.g. 4) instead. */
    fun batteryVoltageMv(raw: Int): Int? = when (raw) {
        in 1..10 -> raw * 1000
        in 1000..10_000 -> raw
        else -> null
    }

    /** EXTRA_LEVEL / EXTRA_SCALE as a percentage. */
    fun batteryLevelPct(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
}
