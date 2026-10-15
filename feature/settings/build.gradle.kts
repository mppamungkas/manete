plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:telephony"))
    // Phone capabilities: device facts (DeviceContextSource).
    implementation(project(":core:device"))
    // Drawable.toBitmap for app icons in the capture app picker (already used by :app and feature:sessions).
    implementation(libs.androidx.core.ktx)
}
