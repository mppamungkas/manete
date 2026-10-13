package id.ac.pnp.netlogger.core.telephony

import id.ac.pnp.netlogger.core.model.raw.DeviceTraits
import id.ac.pnp.netlogger.core.model.raw.RawCellInfoResult
import id.ac.pnp.netlogger.core.model.raw.RawLegacyCellLocation
import id.ac.pnp.netlogger.core.model.raw.RawNetworkState
import id.ac.pnp.netlogger.core.model.raw.RawSubscription
import kotlinx.coroutines.flow.Flow

/** Everything NetLogger reads from TelephonyManager, as raw DTOs. Fakeable for tests. */
interface TelephonySource {
    fun deviceTraits(): DeviceTraits

    /** The active data SIM, or null if there is none or READ_PHONE_STATE is missing. */
    fun activeDataSubscription(): RawSubscription?

    /**
     * Latest DisplayInfo / ServiceState / SignalStrength / data connection state for [subscriptionId]
     * (null = default). The TelephonyCallback is registered while the flow is collected.
     */
    fun networkState(subscriptionId: Int?): Flow<RawNetworkState>

    /** requestCellInfoUpdate(), falling back to getAllCellInfo() after [timeoutMs] or on error. */
    suspend fun requestCellInfo(subscriptionId: Int?, timeoutMs: Long): RawCellInfoResult

    /** Deprecated getCellLocation(); only for the Samsung TAC byte-swap check. */
    fun legacyCellLocation(subscriptionId: Int?): RawLegacyCellLocation?
}
