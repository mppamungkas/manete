package id.my.pamslab.netlogger.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordedCoverageTest {

    private lateinit var db: NetLoggerDatabase
    private lateinit var repo: RoomSessionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NetLoggerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomSessionRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `empty database counts nothing`() = runTest {
        val c = RoomRecordedCoverage(db).coverage()
        assertEquals(0L, c.ticks)
        assertEquals(0L, c.lte.ticks)
        assertEquals(0L, c.lte.rsrp)
    }

    @Test
    fun `counts ticks per field over the serving cells`() = runTest {
        val recorder = repo.startSession(TestData.start())
        // 3 NSA ticks: LTE (CellInfo), NR (CellInfo, no values), NR (SignalStrength with RSRP/RSRQ/SINR).
        repeat(3) { recorder.record(TestData.tick(it.toLong())) }
        // 1 LTE-only tick: no NR, no bandwidths, LTE without RSRP.
        val lteOnly = TestData.tick(3).let { t ->
            t.copy(radio = t.radio.copy(cells = listOf(TestData.lte.copy(rsrpDbm = null)), cellBandwidthsKhz = emptyList()))
        }
        recorder.record(lteOnly)
        // 1 tick with a single bandwidth and a neighbour.
        val neighbour = TestData.tick(4).let { t ->
            t.copy(radio = t.radio.copy(cells = listOf(TestData.lte, TestData.lte.copy(servingInferred = false, pci = 12)), cellBandwidthsKhz = listOf(20_000)))
        }
        recorder.record(neighbour)

        val c = RoomRecordedCoverage(db).coverage()
        assertEquals(1L, c.sessions)
        assertEquals(5L, c.ticks)
        assertEquals(5L, c.lte.ticks)
        assertEquals(4L, c.lte.rsrp)
        assertEquals(5L, c.lte.ta)
        assertEquals(0L, c.lte.sinr)
        assertEquals(5L, c.lte.bandApi)
        assertEquals(3L, c.nr.ticks)
        assertEquals(3L, c.nr.rsrp) // from SignalStrength
        assertEquals(3L, c.nr.arfcn) // from CellInfo
        assertEquals(0L, c.nr.bandApi) // mapped
        assertEquals(3L, c.nrCellInfoTicks)
        assertEquals(3L, c.nrSignalStrengthTicks)
        assertEquals(4L, c.bandwidthTicks)
        assertEquals(3L, c.caHintTicks)
        assertEquals(1L, c.neighborTicks)
    }
}
