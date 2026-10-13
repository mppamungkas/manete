package id.ac.pnp.netlogger.core.telephony

import android.os.SystemClock
import id.ac.pnp.netlogger.core.model.Clock
import javax.inject.Inject

class AndroidClock @Inject constructor() : Clock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNs(): Long = SystemClock.elapsedRealtimeNanos()
}
