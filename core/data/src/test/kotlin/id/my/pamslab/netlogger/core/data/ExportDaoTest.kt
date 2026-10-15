package id.my.pamslab.netlogger.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import id.my.pamslab.netlogger.core.model.session.EndReason
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
class ExportDaoTest {

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
    fun `pages by seq, cells carry their tick, warnings their first tick, other sessions excluded`() = runTest {
        val first = repo.startSession(TestData.start("u-1"))
        repeat(5) { first.record(TestData.tick(it.toLong())) }
        first.close(EndReason.USER_STOP, TestData.START_MS + 5_000, TestData.START_NS)
        val second = repo.startSession(TestData.start("u-2"))
        repeat(2) { second.record(TestData.tick(it.toLong())) }

        val dao = db.exportDao()
        val id = first.sessionId
        assertEquals(listOf(0L, 1L), dao.measurements(id, -1, 2).map { it.seq })
        assertEquals(listOf(2L, 3L), dao.measurements(id, 1, 2).map { it.seq })
        assertEquals(listOf(4L), dao.measurements(id, 3, 2).map { it.seq })
        assertEquals(emptyList<Long>(), dao.measurements(id, 4, 2).map { it.seq })

        val cells = dao.cells(id, 2, 3)
        assertEquals(listOf(2L, 2L, 2L, 3L, 3L, 3L), cells.map { it.seq })
        assertEquals(TestData.START_MS + 2_000, cells.first().wallTimeMs)
        assertEquals(listOf("LTE", "NR", "NR"), cells.take(3).map { it.cell.rat })
        assertEquals("NSA_INFERRED", cells[1].cell.servingSource)

        val warnings = dao.warnings(id)
        assertEquals(listOf(0L, 0L), warnings.map { it.firstSeq })
        assertEquals(5L, warnings.first().warning.occurrences)
        assertEquals(5L, dao.measurementCount(id))
        assertEquals("u-1", dao.session(id)?.uuid)
    }
}
