package id.my.pamslab.netlogger.core.device

import id.my.pamslab.netlogger.core.model.device.LinkCounters
import id.my.pamslab.netlogger.core.model.device.NetworkRoute
import kotlinx.coroutines.flow.Flow

/** The default network and the phone's byte counters, for the Live data row (decision 004). */
interface NetworkRouteSource {
    /** The current default network; listens only while collected. Never throws. */
    val route: Flow<NetworkRoute>

    /** Cheap; never throws. */
    fun counters(): LinkCounters
}
