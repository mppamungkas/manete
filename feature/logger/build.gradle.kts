plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    implementation(project(":core:telephony"))
    implementation(project(":core:location"))
    implementation(project(":core:device"))

    testImplementation(libs.kotlinx.coroutines.test)
}
