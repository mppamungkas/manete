package id.my.pamslab.netlogger.core.export.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.my.pamslab.netlogger.core.export.AndroidHeatmapRenderer
import id.my.pamslab.netlogger.core.export.HeatmapRenderer

@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {
    @Binds
    abstract fun bindHeatmapRenderer(impl: AndroidHeatmapRenderer): HeatmapRenderer
}
