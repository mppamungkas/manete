package id.ac.pnp.netlogger.core.telephony.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.ac.pnp.netlogger.core.model.Clock
import id.ac.pnp.netlogger.core.telephony.AndroidClock
import id.ac.pnp.netlogger.core.telephony.AndroidTelephonySource
import id.ac.pnp.netlogger.core.telephony.TelephonySource
import id.ac.pnp.netlogger.core.telephony.parser.CellParser

@Module
@InstallIn(SingletonComponent::class)
abstract class TelephonyModule {
    @Binds
    abstract fun bindTelephonySource(impl: AndroidTelephonySource): TelephonySource

    @Binds
    abstract fun bindClock(impl: AndroidClock): Clock

    companion object {
        @Provides
        fun provideCellParser(): CellParser = CellParser()
    }
}
