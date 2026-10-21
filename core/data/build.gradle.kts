plugins {
    alias(libs.plugins.netlogger.android.library)
    alias(libs.plugins.netlogger.hilt)
}

android {
    // Exported Room schemas double as test assets for MigrationTestHelper (unit and instrumented tests).
    sourceSets {
        getByName("test").assets.srcDir("$projectDir/schemas")
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:diagnostics")) // CaughtErrors: caught errors for "Describe a problem"
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
