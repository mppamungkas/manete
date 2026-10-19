plugins {
    alias(libs.plugins.netlogger.android.library)
    alias(libs.plugins.netlogger.hilt)
}

dependencies {
    implementation(project(":core:diagnostics")) // CaughtErrors: caught errors for "Describe a problem"
    api(project(":core:model"))
}
