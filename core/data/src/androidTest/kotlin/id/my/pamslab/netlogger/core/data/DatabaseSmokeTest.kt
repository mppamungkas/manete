package id.my.pamslab.netlogger.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import id.my.pamslab.netlogger.core.model.device.BatterySample
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.DeviceInfo
import id.my.pamslab.netlogger.core.model.device.TrafficSample
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.NsaInputs
import id.my.pamslab.netlogger.core.model.radio.ParseWarning
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.radio.WarningCode
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.session.EndReason
import id.my.pamslab.netlogger.core.model.session.SessionStart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test on a real phone's SQLite: create a session, store a measurement with cells and a warning, read it back,
 * and open the exported schema with MigrationTestHelper (add migration checks here once version 2 exists).
 * Not run in CI; must pass on both phones before a release:
 * `./gradlew :core:data:connectedDebugAndroidTest` (with ANDROID_SERIAL set when two phones are attached).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NetLoggerDatabase::class.java)

    @Test
    fun sessionMeasurementCellsAndWarningRoundTrip() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NetLoggerDatabase::class.java).build()
        try {
            val repo = RoomSessionRepository(db)
            val rec = repo.startSession(
                SessionStart("smoke", 1_000, 1_000_000, 1_000, null, DeviceInfo("m", "d", "15", 35, null, null)),
            )
            val cell = CellSample(
                Rat.LTE, true, true, null, 1, 2, 3, 1475, 3, null, 10_000, "510", "89", -90, -10, 5, -60, null, 1,
                null, null, null, null, null, CellSource.CELL_INFO, 100,
            )
            val tick = Tick(
                0,
                RadioSnapshot(
                    1_000, 1_000_000, 1, 0, "3", "51089", NetworkType.LTE, NsaInputs(true, false, false, 0, 13),
                    0, 2, false, listOf(20_000), CellInfoOrigin.FRESH, listOf(cell, cell.copy(isServing = false, pci = 4)),
                    listOf(ParseWarning(WarningCode.OUT_OF_RANGE, Rat.LTE, 3, "rsrp", "464")), null,
                ),
                null, LocationStatus.WAITING, null,
                DeviceContext(BatterySample(null, null, null, null, null), TrafficSample(null, null, null, null), true, 0),
            )
            rec.record(tick)
            rec.close(EndReason.USER_STOP, 2_000, 2_000_000)

            val dao = db.measurementDao()
            val m = dao.measurements(rec.sessionId).single()
            assertEquals(2, dao.cells(m.id).size)
            assertEquals("464", dao.warnings(rec.sessionId).single().rawValue)
            assertEquals("USER_STOP", db.sessionDao().get(rec.sessionId)!!.endReason)
        } finally {
            db.close()
        }
    }

    @Test
    fun exportedSchemaOpens() {
        helper.createDatabase("smoke-schema.db", NetLoggerDatabase.VERSION).close()
    }
}
