package id.ac.pnp.netlogger.core.telephony

import id.ac.pnp.netlogger.core.model.Clock
import id.ac.pnp.netlogger.core.model.radio.NetworkType
import id.ac.pnp.netlogger.core.model.raw.CellInfoOrigin
import id.ac.pnp.netlogger.core.model.raw.RawCellInfoResult
import id.ac.pnp.netlogger.core.model.raw.RawDisplayInfo
import id.ac.pnp.netlogger.core.model.raw.RawLegacyCellLocation
import id.ac.pnp.netlogger.core.model.raw.RawNetworkState
import id.ac.pnp.netlogger.core.model.raw.RawSubscription
import id.ac.pnp.netlogger.core.telephony.parser.CellParser
import id.ac.pnp.netlogger.core.telephony.parser.MOTO
import id.ac.pnp.netlogger.core.telephony.parser.lteCell
import id.ac.pnp.netlogger.core.telephony.parser.nrSignal
import id.ac.pnp.netlogger.core.telephony.parser.signalStrength
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadioSamplerTest {

    private class VirtualClock(private val scope: TestScope) : Clock {
        override fun wallTimeMs() = 1_700_000_000_000L + scope.currentTime
        override fun elapsedRealtimeNs() = scope.currentTime * 1_000_000L
    }

    private class FakeSource(private val cellInfoDelayMs: Long = 0) : TelephonySource {
        var requests = 0
        override fun deviceTraits() = MOTO
        override fun activeDataSubscription() = RawSubscription(3, 0, "Telkomsel", "510", "10")
        override fun networkState(subscriptionId: Int?): Flow<RawNetworkState> = flowOf(
            RawNetworkState(signalStrength = signalStrength(nr = listOf(nrSignal()), timestamp = null), displayInfo = RawDisplayInfo(13, 3)),
        )
        override suspend fun requestCellInfo(subscriptionId: Int?, timeoutMs: Long): RawCellInfoResult {
            requests++
            delay(cellInfoDelayMs)
            return RawCellInfoResult(listOf(lteCell(timestamp = null)), CellInfoOrigin.FRESH)
        }
        override fun legacyCellLocation(subscriptionId: Int?): RawLegacyCellLocation? = null
    }

    @Test
    fun `ticks on elapsedRealtime deadlines without drift`() = runTest {
        val source = FakeSource(cellInfoDelayMs = 300)
        val snapshots = RadioSampler(source, CellParser(), VirtualClock(this)).samples(1_000).take(4).toList()

        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), snapshots.map { it.elapsedRealtimeNs / 1_000_000 })
        assertEquals(NetworkType.NR_NSA, snapshots.last().networkType)
        assertEquals(3, snapshots.last().subscriptionId)
    }

    @Test
    fun `an overrunning tick skips ahead instead of bursting`() = runTest {
        val source = FakeSource(cellInfoDelayMs = 1_500)
        val snapshots = RadioSampler(source, CellParser(), VirtualClock(this)).samples(1_000).take(3).toList()

        assertEquals(listOf(0L, 1_500L, 3_000L), snapshots.map { it.elapsedRealtimeNs / 1_000_000 })
    }
}
