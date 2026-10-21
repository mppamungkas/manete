plugins {
    alias(libs.plugins.netlogger.android.library)
    alias(libs.plugins.netlogger.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:data"))
    // Heatmaps (decision 006 step 7): the Map's colour rules (SignalLevel); CaughtErrors.
    implementation(project(":core:ui"))
    implementation(project(":core:diagnostics"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
