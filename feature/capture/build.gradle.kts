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
    // Drive tests need a phone's modem: ChromeOS (x86_64) is not a target.
    lint { disable += "ChromeOsAbiSupport" }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

dependencies {
    implementation(project(":core:diagnostics")) // CaughtErrors: caught errors for "Describe a problem"
    implementation(project(":core:data"))
    // CsvWriter and IsoTime for connections.csv: the same CSV rules as the session export.
    implementation(project(":core:export"))
    implementation(libs.kotlinx.coroutines.core)
    // rememberLauncherForActivityResult: the system VPN consent dialog (already used by :app).
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// Host-side C tests of libnetcapture (pcap writer, clock), built with the SDK's CMake and the host compiler
// (gcc/clang with AddressSanitizer + UBSan). Part of testDebugUnitTest, so the pre-commit command and CI run them.
val nativeHostTest = tasks.register<Exec>("nativeHostTest") {
    group = "verification"
    description = "Builds and runs libnetcapture's host-side C tests."
    val cmakeBin = android.sdkDirectory.resolve("cmake/${libs.versions.cmake.get()}/bin")
    val buildDir = layout.buildDirectory.dir("nativeHostTest").get().asFile
    val passed = layout.buildDirectory.file("nativeHostTest/passed").get().asFile
    inputs.dir("src/main/cpp")
    inputs.dir("src/test/cpp")
    outputs.file(passed)
    commandLine("sh", file("src/test/cpp/run_host_tests.sh").absolutePath, cmakeBin.absolutePath, buildDir.absolutePath)
    doLast { passed.writeText("ok\n") }
}
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn(nativeHostTest) }
