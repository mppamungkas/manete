package id.my.pamslab.netlogger.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import id.my.pamslab.netlogger.core.model.logging.SessionEvent
import id.my.pamslab.netlogger.core.model.logging.UserMarker
import id.my.pamslab.netlogger.core.model.session.EndReason
import id.my.pamslab.netlogger.core.model.session.EventType
import id.my.pamslab.netlogger.core.model.session.Scenario
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSessionRepositoryTest {

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
    fun `ticks are stored with cells, deduplicated warnings and session network info`() = runTest {
        val rec = repo.startSession(TestData.start())
        repeat(3) { rec.record(TestData.tick(it.toLong())) }

        val dao = db.measurementDao()
        val ms = dao.measurements(rec.sessionId)
        assertEquals(listOf(0L, 1L, 2L), ms.map { it.seq })
        val cells = dao.cells(ms.first().id)
        assertEquals(listOf("LTE", "NR", "NR"), cells.map { it.rat })
        assertEquals(listOf("CELL_INFO", "CELL_INFO", "SIGNAL_STRENGTH"), cells.map { it.source })
        assertEquals(-100, cells.last().rsrpDbm)

        val warnings = dao.warnings(rec.sessionId)
        assertEquals(2, warnings.size)
        assertTrue(warnings.all { it.occurrences == 3L })
        assertEquals(ms.first().id, warnings.first().measurementId)
        assertEquals(TestData.START_MS + 2_000, warnings.first().lastSeenMs)

        val session = db.sessionDao().get(rec.sessionId)!!
        assertEquals("510", session.mcc)
        assertEquals("89", session.mnc)
        assertEquals("3", session.operatorName)
        assertEquals(0, session.simSlot)
        assertEquals("STATIC", session.scenario)
        assertEquals(NetLoggerDatabase.VERSION, session.schemaVersion)
        assertNull(session.endedAt)
    }

    @Test
    fun `markers become USER_MARKER events and close sets the end`() = runTest {
        val rec = repo.startSession(TestData.start())
        rec.record(TestData.tick(0))
        rec.addMarker(UserMarker(TestData.START_MS + 500, TestData.START_NS + 500_000_000, -0.9, 100.4))
        rec.close(EndReason.USER_STOP, TestData.START_MS + 9_000, TestData.START_NS + 9_000_000_000)

        val event = db.measurementDao().events(rec.sessionId).single()
        assertEquals("USER_MARKER", event.type)
        assertEquals(-0.9, event.lat!!, 0.0)
        val summary = repo.observeSession(rec.sessionId).first()!!
        assertEquals(EndReason.USER_STOP, summary.endReason)
        assertEquals(TestData.START_MS + 9_000, summary.endedAtMs)
        assertEquals(1L, summary.measurements)
        assertEquals(3L, summary.cells)
        assertEquals(1L, summary.markers)
        assertEquals(2L, summary.warnings)
    }

    @Test
    fun `abandoned sessions are closed at their last measurement, the active one is kept`() = runTest {
        val killed = repo.startSession(TestData.start("killed"))
        repeat(5) { killed.record(TestData.tick(it.toLong())) }
        // Process killed: a fresh repository (new process) finds the session open.
        val afterRestart = RoomSessionRepository(db)
        val active = afterRestart.startSession(TestData.start("active").copy(startedAtMs = TestData.START_MS + 60_000))
        assertEquals(0, afterRestart.closeAbandonedSessions())

        val closed = db.sessionDao().get(killed.sessionId)!!
        assertEquals("RECOVERED_AFTER_KILL", closed.endReason)
        assertEquals(TestData.START_MS + 4_000, closed.endedAt)
        assertEquals(TestData.START_NS + 4_000_000_000, closed.endedElapsedNs)
        assertNull("recording session untouched", db.sessionDao().get(active.sessionId)!!.endedAt)
    }

    @Test
    fun `abandoned session without ticks ends at its start`() = runTest {
        val empty = repo.startSession(TestData.start("empty"))
        assertEquals(1, RoomSessionRepository(db).closeAbandonedSessions())
        assertEquals(TestData.START_MS, db.sessionDao().get(empty.sessionId)!!.endedAt)
    }

    @Test
    fun `delete cascades to measurements, cells, events and warnings`() = runTest {
        val rec = repo.startSession(TestData.start())
        rec.record(TestData.tick(0))
        rec.addMarker(UserMarker(0, 0, null, null))
        rec.close(EndReason.USER_STOP, TestData.START_MS + 1_000, TestData.START_NS)
        val measurementId = db.measurementDao().measurements(rec.sessionId).single().id

        repo.delete(rec.sessionId)

        assertNull(db.sessionDao().get(rec.sessionId))
        assertTrue(db.measurementDao().measurements(rec.sessionId).isEmpty())
        assertTrue(db.measurementDao().cells(measurementId).isEmpty())
        assertTrue(db.measurementDao().events(rec.sessionId).isEmpty())
        assertTrue(db.measurementDao().warnings(rec.sessionId).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the recording session cannot be deleted`() = runTest {
        repo.delete(repo.startSession(TestData.start()).sessionId)
    }

    @Test
    fun `details update and latest scenario`() = runTest {
        val rec = repo.startSession(TestData.start())
        repo.updateDetails(rec.sessionId, " Padang loop ", "", Scenario.DRIVE)
        val s = repo.observeSession(rec.sessionId).first()!!
        assertEquals(" Padang loop ", s.label)
        assertNull("blank notes are stored as null", s.notes)
        assertEquals(Scenario.DRIVE, repo.latestScenario())
    }

    @Test
    fun `detected events are stored with type, from, to and location`() = runTest {
        val rec = repo.startSession(TestData.start())
        rec.addEvent(SessionEvent(EventType.DATA_SIM_CHANGED, TestData.START_MS, TestData.START_NS, "5", "1", -0.9, 100.4))
        val e = db.measurementDao().events(rec.sessionId).single()
        assertEquals("DATA_SIM_CHANGED", e.type)
        assertEquals("5" to "1", e.fromValue to e.toValue)
        assertEquals(-0.9, e.lat!!, 0.0)
    }
}
