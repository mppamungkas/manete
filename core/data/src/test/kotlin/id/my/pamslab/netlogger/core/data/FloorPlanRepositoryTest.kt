package id.my.pamslab.netlogger.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import id.my.pamslab.netlogger.core.model.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FloorPlanRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: NetLoggerDatabase
    private lateinit var repo: RoomFloorPlanRepository
    private var nextResult: Result<Bitmap> = Result.success(Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888))

    private val reader = object : PlanImageReader {
        override fun inspect(uri: Uri) = Result.success(PlanSource("plan.pdf", 3))
        override fun read(uri: Uri, pdfPage: Int) = nextResult
    }

    private val clock = object : Clock {
        var now = 1_792_500_000_000L
        override fun wallTimeMs() = now++
        override fun elapsedRealtimeNs() = 0L
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NetLoggerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val files = object : PlanFileStore {
            override val dir: File = File(tmp.root, "plans")
        }
        repo = RoomFloorPlanRepository(db, files, reader, clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun import(name: String = " Building C ", floor: String? = " 2nd floor "): Long {
        val result = repo.import(Uri.parse("content://test/plan"), 0, name, floor)
        return (result as PlanImportResult.Imported).id
    }

    @Test
    fun `import stores the image as PNG with its size, trimmed name and floor`() = runTest {
        val id = import()
        val plan = repo.plan(id)!!
        assertEquals("Building C", plan.plan.name)
        assertEquals("2nd floor", plan.plan.floor)
        assertEquals(1600, plan.plan.widthPx)
        assertEquals(900, plan.plan.heightPx)
        assertNull(plan.plan.metresPerPx)
        assertTrue(plan.image.isFile)
        assertTrue(plan.image.name.endsWith(".png"))
        assertEquals(listOf(id), repo.plans.first().map { it.plan.id })
    }

    @Test
    fun `empty name and floor fall back, a failed read stores nothing`() = runTest {
        val plan = repo.plan(import(name = "  ", floor = " "))!!.plan
        assertEquals("Floor plan", plan.name)
        assertNull(plan.floor)

        nextResult = Result.failure(PlanImageException(PlanImportError.PDF_PROTECTED))
        assertEquals(PlanImportResult.Failed(PlanImportError.PDF_PROTECTED), repo.import(Uri.parse("content://test/x"), 0, "x", null))
        assertEquals(1, repo.plans.first().size)
        assertEquals(1, File(tmp.root, "plans").listFiles()!!.count { it.name.endsWith(".png") })
    }

    @Test
    fun `rename and scale`() = runTest {
        val id = import()
        repo.rename(id, "Library", null)
        repo.setScale(id, 0.05)
        repo.plan(id)!!.plan.let {
            assertEquals("Library", it.name)
            assertNull(it.floor)
            assertEquals(0.05, it.metresPerPx!!, 0.0)
        }
        repo.setScale(id, null)
        assertNull(repo.plan(id)!!.plan.metresPerPx)
    }

    @Test
    fun `a plan used by a session is kept, an unused one is deleted with its image`() = runTest {
        val used = import()
        val unused = import(name = "Basement")
        val session = RoomSessionRepository(db).startSession(TestData.start()).sessionId
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        // An undone tap (step 3 writes taps through the logging engine).
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO plan_waypoint (sessionId, planId, wallTimeMs, elapsedRealtimeNs, x, y, kind, removed) VALUES " +
                "($session, $used, 1, 1, 1.0, 2.0, 'HERE', 1)",
        )
        val summaries = repo.plans.first().associateBy { it.plan.id }
        assertEquals(1, summaries.getValue(used).sessions)
        assertEquals("an undone tap is not counted as a waypoint", 0, summaries.getValue(used).waypoints)

        assertFalse("even an undone tap keeps the plan", repo.delete(used))
        assertTrue(repo.plan(used)!!.image.isFile)

        val image = repo.plan(unused)!!.image
        assertTrue(repo.delete(unused))
        assertNull(repo.plan(unused))
        assertFalse(image.exists())
    }
}
