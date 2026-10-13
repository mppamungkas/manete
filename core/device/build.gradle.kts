plugins {
    alias(libs.plugins.netlogger.android.library)
    alias(libs.plugins.netlogger.hilt)
}

dependencies {
    api(project(":core:model"))
}
