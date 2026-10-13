package id.ac.pnp.netlogger.core.device.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.ac.pnp.netlogger.core.device.AndroidDeviceContextSource
import id.ac.pnp.netlogger.core.device.DeviceContextSource

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceModule {
    @Binds
    abstract fun bindDeviceContextSource(impl: AndroidDeviceContextSource): DeviceContextSource
}
