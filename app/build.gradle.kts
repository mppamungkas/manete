plugins {
    alias(libs.plugins.netlogger.android.application)
    alias(libs.plugins.netlogger.android.compose)
    alias(libs.plugins.netlogger.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "id.my.pamslab.netlogger"

    // Timber arrives with MapLibre and ships a lint rule against android.util.Log; NetLogger logs with Log and the
    // "NetLogger" tag on purpose (adb logcat -s NetLogger:*).
    lint.disable += "LogNotTimber"

    defaultConfig {
        applicationId = "id.my.pamslab.netlogger"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":feature:logger"))
    implementation(project(":feature:map"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
