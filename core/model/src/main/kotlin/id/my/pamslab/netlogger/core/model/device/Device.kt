package id.my.pamslab.netlogger.core.model.device

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

/** Which network carries the phone's traffic now (the default network). Shown on Live only, not stored (decision 004). */
data class NetworkRoute(
    val transport: Transport,
    /** Wi-Fi network name without quotes; null when not Wi-Fi, hidden or not readable (location off). */
    val ssid: String? = null,
    /** The default network is a VPN (e.g. NetLogger's own capture); [transport] is then its underlying network. */
    val vpn: Boolean = false,
) {
    enum class Transport { CELLULAR, WIFI, ETHERNET, OTHER, NONE }
}

/** Byte counters since boot, read together; null where the phone does not report them. */
data class LinkCounters(
    val elapsedRealtimeNs: Long,
    val mobileRxBytes: Long?,
    val mobileTxBytes: Long?,
    val totalRxBytes: Long?,
    val totalTxBytes: Long?,
)
