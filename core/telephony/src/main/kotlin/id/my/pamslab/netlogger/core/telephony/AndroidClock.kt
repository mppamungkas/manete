package id.my.pamslab.netlogger.core.telephony

import android.os.SystemClock
import id.my.pamslab.netlogger.core.model.Clock
import javax.inject.Inject

class AndroidClock @Inject constructor() : Clock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNs(): Long = SystemClock.elapsedRealtimeNanos()
}
