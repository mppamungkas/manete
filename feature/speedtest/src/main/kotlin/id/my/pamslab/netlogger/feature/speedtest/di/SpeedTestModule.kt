package id.my.pamslab.netlogger.feature.speedtest.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestRunner
import id.my.pamslab.netlogger.feature.speedtest.CellularNetworkGuard
import id.my.pamslab.netlogger.feature.speedtest.NetworkGuard
import id.my.pamslab.netlogger.feature.speedtest.OkHttpSpeedTestRunner

@Module
@InstallIn(SingletonComponent::class)
abstract class SpeedTestModule {
    @Binds
    abstract fun bindRunner(impl: OkHttpSpeedTestRunner): SpeedTestRunner

    @Binds
    abstract fun bindNetworkGuard(impl: CellularNetworkGuard): NetworkGuard
}
