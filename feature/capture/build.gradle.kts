plugins {
    alias(libs.plugins.netlogger.android.feature)
}

android {
    // Native capture (Phase 3b): libnetcapture, built with the pinned NDK and the SDK's CMake.
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        // Both test phones are arm64; armeabi-v7a keeps 32-bit phones working. No emulator ABIs (no emulator).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)
    // rememberLauncherForActivityResult: PCAPdroid's API wants startActivityForResult (already used by :app).
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
