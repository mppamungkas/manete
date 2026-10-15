package id.my.pamslab.netlogger.core.telephony

import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.WarningCode
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.raw.RawCellInfoResult
import id.my.pamslab.netlogger.core.model.raw.RawDisplayInfo
import id.my.pamslab.netlogger.core.model.raw.RawLegacyCellLocation
import id.my.pamslab.netlogger.core.model.raw.RawNetworkState
import id.my.pamslab.netlogger.core.model.raw.RawSubscription
import id.my.pamslab.netlogger.core.telephony.parser.CellParser
import id.my.pamslab.netlogger.core.telephony.parser.MOTO
import id.my.pamslab.netlogger.core.telephony.parser.lteCell
import id.my.pamslab.netlogger.core.telephony.parser.nrSignal
import id.my.pamslab.netlogger.core.telephony.parser.signalStrength
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
        var dataSubId: Int? = 3
        override fun deviceTraits() = MOTO
        override fun activeDataSubscription() = RawSubscription(3, 0, "Telkomsel", "510", "10")
        override fun defaultDataSubscriptionId() = dataSubId
        override fun networkState(subscriptionId: Int?): Flow<RawNetworkState> = flowOf(
            RawNetworkState(signalStrength = signalStrength(nr = listOf(nrSignal()), timestamp = null), displayInfo = RawDisplayInfo(13, 3)),
        )
        val cellInfoSubIds = mutableListOf<Int?>()
        override suspend fun requestCellInfo(subscriptionId: Int?, timeoutMs: Long): RawCellInfoResult {
            requests++
            cellInfoSubIds += subscriptionId
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

    @Test
    fun `a data SIM switch keeps the session SIM and flags every tick after it`() = runTest {
        val source = FakeSource()
        val snapshots = mutableListOf<id.my.pamslab.netlogger.core.model.radio.RadioSnapshot>()
        RadioSampler(source, CellParser(), VirtualClock(this)).samples(1_000).take(3).collect {
            snapshots += it
            source.dataSubId = 5 // user switched the data SIM to subId 5 after the first tick
        }
        assertEquals(listOf(3, 3, 3), snapshots.map { it.subscriptionId })
        assertEquals("the session SIM is still the one queried", listOf(3, 3, 3), source.cellInfoSubIds)
        assertEquals(listOf(3, 5, 5), snapshots.map { it.dataSubscriptionId })
        val flagged = snapshots.map { s -> s.warnings.filter { it.code == WarningCode.DATA_SIM_CHANGED }.map { it.rawValue } }
        assertEquals(listOf(emptyList(), listOf("5"), listOf("5")), flagged)
        assertEquals("a flagged correction, not a parse error", 0, snapshots.last().parseErrors)
    }
}
