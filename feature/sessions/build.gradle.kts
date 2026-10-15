plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:export"))
    // FileProvider for sharing the export ZIP (already used by :app).
    implementation(libs.androidx.core.ktx)
    // "Save to…" document picker (already used by :app).
    implementation(libs.androidx.activity.compose)
}
