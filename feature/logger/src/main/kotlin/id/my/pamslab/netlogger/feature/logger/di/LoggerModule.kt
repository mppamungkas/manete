package id.my.pamslab.netlogger.feature.logger.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.my.pamslab.netlogger.core.telephony.RadioSampler
import id.my.pamslab.netlogger.feature.logger.service.RadioSamples

@Module
@InstallIn(SingletonComponent::class)
object LoggerModule {
    @Provides
    fun provideRadioSamples(sampler: RadioSampler): RadioSamples = RadioSamples(sampler::samples)
}
