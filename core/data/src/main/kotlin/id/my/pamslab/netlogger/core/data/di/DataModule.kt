package id.my.pamslab.netlogger.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.my.pamslab.netlogger.core.data.RoomSessionRepository
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.data.db.ExportDao
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindSessionRepository(impl: RoomSessionRepository): SessionRepository

    companion object {
        // No fallbackToDestructiveMigration: losing field data silently is worse than a crash that names the
        // missing migration.
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): NetLoggerDatabase =
            Room.databaseBuilder(context, NetLoggerDatabase::class.java, NetLoggerDatabase.NAME).build()

        @Provides
        fun provideExportDao(db: NetLoggerDatabase): ExportDao = db.exportDao()
    }
}
