package id.my.pamslab.netlogger.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** Bump [VERSION] only together with a Migration and a migration test (CLAUDE.md). */
@Database(
    entities = [SessionEntity::class, MeasurementEntity::class, CellEntity::class, EventEntity::class, ParseWarningEntity::class],
    version = NetLoggerDatabase.VERSION,
    exportSchema = true,
)
abstract class NetLoggerDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        const val VERSION = 1
        const val NAME = "netlogger.db"
    }
}
