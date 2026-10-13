package id.ac.pnp.netlogger.core.model.device

/** Battery values as reported. Sign and scale of [currentUa] vary by OEM; recorded raw. */
data class BatterySample(
    val levelPct: Int?,
    val currentUa: Int?,
    val voltageMv: Int?,
    val tempC: Float?,
    val isCharging: Boolean?,
)

/** Cumulative TrafficStats byte counters since boot. Null when the device does not support them. */
data class TrafficSample(
    val rxBytesTotal: Long?,
    val txBytesTotal: Long?,
    val rxBytesApp: Long?,
    val txBytesApp: Long?,
)

/** Device context read once per tick. */
data class DeviceContext(
    val battery: BatterySample,
    val traffic: TrafficSample,
    val screenOn: Boolean?,
    /** PowerManager.THERMAL_STATUS_* (0 = none … 6 = shutdown). */
    val thermalStatus: Int?,
)

/** Static device facts for session metadata. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    /** Build.getRadioVersion(); null when the modem has not reported it yet. */
    val basebandVersion: String?,
    val appVersion: String?,
)
