plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    implementation(project(":core:telephony"))
    implementation(project(":core:location"))
    implementation(project(":core:device"))
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics"))

    testImplementation(libs.kotlinx.coroutines.test)
}
