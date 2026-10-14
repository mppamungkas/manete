plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)
    // rememberLauncherForActivityResult: PCAPdroid's API wants startActivityForResult (already used by :app).
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
