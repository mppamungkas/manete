package id.my.pamslab.netlogger.core.telephony

import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.radio.ParseWarning
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.radio.WarningCode
import id.my.pamslab.netlogger.core.model.raw.RawNetworkState
import id.my.pamslab.netlogger.core.telephony.parser.CellParser
import id.my.pamslab.netlogger.core.telephony.parser.NetworkClassifier
import id.my.pamslab.netlogger.core.telephony.parser.ParseInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import javax.inject.Inject

/**
 * Radio tick loop: every [samples] interval, request a cell info update, combine it with the latest
 * TelephonyCallback state and parse it into a [RadioSnapshot]. Ticks are scheduled on elapsedRealtime
 * deadlines, so they don't drift; a tick that overruns skips ahead instead of bursting to catch up.
 */
class RadioSampler @Inject constructor(
    private val source: TelephonySource,
    private val parser: CellParser,
    private val clock: Clock,
) {
    fun samples(intervalMs: Long): Flow<RadioSnapshot> = channelFlow {
        require(intervalMs > 0)
        val subscription = source.activeDataSubscription()
        val subId = subscription?.subscriptionId
        val device = source.deviceTraits()
        val network = source.networkState(subId).stateIn(this, SharingStarted.Eagerly, RawNetworkState())
        val intervalNs = intervalMs * NS_PER_MS
        // Leave headroom in the tick for parsing and the DB insert.
        val cellInfoTimeoutMs = (intervalMs * 3 / 4).coerceIn(MIN_CELL_INFO_TIMEOUT_MS, MAX_CELL_INFO_TIMEOUT_MS)
        var tacSwapLatched = false
        var deadline = clock.elapsedRealtimeNs()

        while (isActive) {
            val tickElapsedNs = clock.elapsedRealtimeNs()
            val tickWallMs = clock.wallTimeMs()
            val cellInfo = source.requestCellInfo(subId, cellInfoTimeoutMs)
            val state = network.value
            val serviceState = state.serviceState
            val result = parser.parse(
                ParseInput(
                    cellInfo = cellInfo,
                    signalStrength = state.signalStrength,
                    device = device,
                    nowElapsedMs = clock.elapsedRealtimeNs() / NS_PER_MS,
                    legacyCellLocation = if (device.isSamsung) source.legacyCellLocation(subId) else null,
                    samsungTacSwapLatched = tacSwapLatched,
                    cellBandwidthsKhz = serviceState?.cellBandwidthsKhz,
                    overrideNetworkType = state.displayInfo?.overrideNetworkType,
                    registeredMcc = serviceState?.operatorNumeric?.takeIf { it.length >= 5 }?.take(3),
                ),
            )
            tacSwapLatched = result.samsungTacSwapLatched

            val dataNetworkType = state.displayInfo?.networkType ?: state.dataConnection?.networkType
            val inputs = NetworkClassifier.nsaInputs(
                cells = result.cells,
                nrSignalStrengthPresent = result.nrSignalStrengthPresent,
                overrideNetworkType = state.displayInfo?.overrideNetworkType,
                dataNetworkType = dataNetworkType,
            )
            val extraWarnings = serviceState?.extractionFailures?.takeIf { it > 0 }?.let {
                listOf(
                    ParseWarning(
                        WarningCode.EXTRACTION_FAILED,
                        field = "serviceState",
                        rawValue = it.toString(),
                    ),
                )
            }.orEmpty()
            // The session stays on the SIM it started with (its metadata describes that SIM); a switch is flagged.
            val dataSubId = source.defaultDataSubscriptionId()
            val simWarnings = if (subId != null && dataSubId != subId) {
                listOf(ParseWarning(WarningCode.DATA_SIM_CHANGED, field = "subscriptionId", rawValue = dataSubId?.toString()))
            } else {
                emptyList()
            }

            send(
                RadioSnapshot(
                    wallTimeMs = tickWallMs,
                    elapsedRealtimeNs = tickElapsedNs,
                    subscriptionId = subId,
                    simSlot = subscription?.simSlot,
                    operatorName = serviceState?.operatorAlphaLong ?: subscription?.carrierName,
                    operatorNumeric = serviceState?.operatorNumeric,
                    networkType = NetworkClassifier.classify(inputs, result.cells, serviceState?.state),
                    nsaInputs = inputs,
                    serviceState = serviceState?.state,
                    dataState = state.dataConnection?.state,
                    isRoaming = serviceState?.isRoaming,
                    cellBandwidthsKhz = serviceState?.cellBandwidthsKhz.orEmpty(),
                    cellInfoOrigin = cellInfo.origin,
                    cells = result.cells,
                    warnings = result.warnings + extraWarnings + simWarnings,
                    listenerError = state.registrationError,
                    dataSubscriptionId = dataSubId,
                ),
            )

            deadline += intervalNs
            val now = clock.elapsedRealtimeNs()
            if (deadline < now) deadline = now
            delay((deadline - now) / NS_PER_MS)
        }
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
        const val MIN_CELL_INFO_TIMEOUT_MS = 250L
        const val MAX_CELL_INFO_TIMEOUT_MS = 2_000L
    }
}
