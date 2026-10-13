package id.my.pamslab.netlogger.core.device

import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.DeviceInfo

interface DeviceContextSource {
    /** Cheap enough to call once per tick. Never throws; unknown values are null. */
    fun snapshot(): DeviceContext

    fun deviceInfo(): DeviceInfo
}
