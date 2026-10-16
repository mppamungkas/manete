package id.my.pamslab.netlogger.core.device.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.my.pamslab.netlogger.core.device.AndroidDeviceContextSource
import id.my.pamslab.netlogger.core.device.DeviceContextSource
import id.my.pamslab.netlogger.core.device.AndroidNetworkRouteSource
import id.my.pamslab.netlogger.core.device.NetworkRouteSource

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceModule {
    @Binds
    abstract fun bindDeviceContextSource(impl: AndroidDeviceContextSource): DeviceContextSource

    @Binds
    abstract fun bindNetworkRouteSource(impl: AndroidNetworkRouteSource): NetworkRouteSource
}
