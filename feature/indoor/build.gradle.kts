plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    // Floor plans: repository, stored images (decision 006).
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics"))
    implementation(libs.kotlinx.coroutines.core)
    // Import: the system file picker (OpenDocument), as feature:sessions and feature:capture use it.
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
