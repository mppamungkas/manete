package id.my.pamslab.netlogger.core.telephony.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.telephony.AndroidClock
import id.my.pamslab.netlogger.core.telephony.AndroidTelephonySource
import id.my.pamslab.netlogger.core.telephony.TelephonySource
import id.my.pamslab.netlogger.core.telephony.parser.BandMapper
import id.my.pamslab.netlogger.core.telephony.parser.CellParser

@Module
@InstallIn(SingletonComponent::class)
abstract class TelephonyModule {
    @Binds
    abstract fun bindTelephonySource(impl: AndroidTelephonySource): TelephonySource

    @Binds
    abstract fun bindClock(impl: AndroidClock): Clock

    companion object {
        @Provides
        fun provideCellParser(): CellParser = CellParser(BandMapper.Standard)
    }
}
