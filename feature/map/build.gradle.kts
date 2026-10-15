plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    // FlatRow: the same serving-cell and source rule as flat.csv, so map and export agree.
    implementation(project(":core:export"))
    implementation(libs.maplibre.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
