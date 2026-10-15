plugins {
    alias(libs.plugins.netlogger.android.library)
    alias(libs.plugins.netlogger.hilt)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
