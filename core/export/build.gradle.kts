plugins {
    alias(libs.plugins.netlogger.android.library)
    alias(libs.plugins.netlogger.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:data"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
