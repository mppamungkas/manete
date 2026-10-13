package id.my.pamslab.netlogger.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creates the database from the exported schema JSON. Migration tests (version 2 onwards) go here: create the old
 * version with [helper], insert rows, then `helper.runMigrationsAndValidate(name, N, true, MIGRATION_X_N)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SchemaTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NetLoggerDatabase::class.java)

    @Test
    fun `exported schema v1 creates and opens`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO session (uuid, startedAt, startedElapsedNs, intervalMs, deviceManufacturer, deviceModel, " +
                    "androidVersion, schemaVersion) VALUES ('u', 1, 1, 1000, 'm', 'd', '15', 1)",
            )
        }
    }

    private companion object {
        const val DB = "schema-test.db"
    }
}
