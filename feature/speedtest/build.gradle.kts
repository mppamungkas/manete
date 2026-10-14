plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    // HTTP ping, download and upload (CLAUDE.md tech stack; Apache 2.0).
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
