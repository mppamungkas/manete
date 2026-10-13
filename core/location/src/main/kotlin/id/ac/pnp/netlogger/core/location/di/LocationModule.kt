package id.ac.pnp.netlogger.core.location.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.ac.pnp.netlogger.core.location.FusedLocationProvider
import id.ac.pnp.netlogger.core.location.LocationProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds
    abstract fun bindLocationProvider(impl: FusedLocationProvider): LocationProvider
}
