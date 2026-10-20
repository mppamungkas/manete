package id.my.pamslab.netlogger.core.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import id.my.pamslab.netlogger.core.model.device.BatterySample
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.DeviceInfo
import id.my.pamslab.netlogger.core.model.device.TrafficSample
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDeviceContextSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceContextSource {

    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val uid = Process.myUid()

    override fun snapshot() = DeviceContext(
        battery = safe("battery") { battery() } ?: BatterySample(null, null, null, null, null),
        traffic = safe("traffic") { traffic() } ?: TrafficSample(null, null, null, null),
        screenOn = safe("screen") { powerManager?.isInteractive },
        thermalStatus = safe("thermal") { powerManager?.currentThermalStatus },
    )

    private fun battery(): BatterySample {
        // Sticky broadcast: registering a null receiver only returns the last ACTION_BATTERY_CHANGED intent.
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return BatterySample(
            levelPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.let(DeviceValues::batteryProperty)
                ?: sticky?.let {
                    DeviceValues.batteryLevelPct(
                        it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                        it.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                    )
                },
            currentUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.let(DeviceValues::batteryCurrentUa),
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.let(DeviceValues::batteryVoltageMv),
            tempC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.let(DeviceValues::batteryTempC),
            isCharging = when {
                sticky == null -> null
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL -> true
                plugged > 0 -> true
                else -> false
            },
        )
    }

    private fun traffic() = TrafficSample(
        rxBytesTotal = DeviceValues.trafficBytes(TrafficStats.getTotalRxBytes()),
        txBytesTotal = DeviceValues.trafficBytes(TrafficStats.getTotalTxBytes()),
        rxBytesApp = DeviceValues.trafficBytes(TrafficStats.getUidRxBytes(uid)),
        txBytesApp = DeviceValues.trafficBytes(TrafficStats.getUidTxBytes(uid)),
    )

    override fun deviceInfo() = DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidVersion = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        basebandVersion = safe("baseband") { Build.getRadioVersion() }?.takeIf { it.isNotBlank() },
        appVersion = safe("appVersion") { context.packageManager.getPackageInfo(context.packageName, 0).versionName },
    )

    private inline fun <T> safe(field: String, block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        CaughtErrors.caught("device context: $field failed", e)
        null
    }

    private companion object {
        const val TAG = "NetLogger"
    }
}
