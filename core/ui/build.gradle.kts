plugins {
    alias(libs.plugins.netlogger.android.library)
    alias(libs.plugins.netlogger.android.compose)
}

dependencies {
    // PlanView: decode failures are recorded as caught errors (decision 005).
    implementation(project(":core:diagnostics"))
    implementation(libs.kotlinx.coroutines.core)
}
