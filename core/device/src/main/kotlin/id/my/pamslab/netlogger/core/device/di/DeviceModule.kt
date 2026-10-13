package id.my.pamslab.netlogger.core.device.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.my.pamslab.netlogger.core.device.AndroidDeviceContextSource
import id.my.pamslab.netlogger.core.device.DeviceContextSource

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceModule {
    @Binds
    abstract fun bindDeviceContextSource(impl: AndroidDeviceContextSource): DeviceContextSource
}
