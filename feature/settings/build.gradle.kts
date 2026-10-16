plugins {
    alias(libs.plugins.netlogger.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:telephony"))
    // Phone capabilities: device facts (DeviceContextSource).
    implementation(project(":core:device"))
    // Problem reports (decision 005).
    implementation(project(":core:diagnostics"))
    // Drawable.toBitmap for app icons in the capture app picker (already used by :app and feature:sessions).
    implementation(libs.androidx.core.ktx)
}

/**
 * Settings › About shows the full licence texts. GPL v3 (NetLogger's LICENSE) and LGPL v3 (zdtun's COPYING) are
 * copied from their single source in the repo at build time; the other texts are in res/raw.
 */
abstract class LicenceTextsTask : DefaultTask() {
    @get:InputFile abstract val gpl: RegularFileProperty
    @get:InputFile abstract val lgpl: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val raw = outputDir.get().dir("raw").asFile.apply { mkdirs() }
        gpl.get().asFile.copyTo(raw.resolve("licence_gpl_3_0.txt"), overwrite = true)
        lgpl.get().asFile.copyTo(raw.resolve("licence_lgpl_3_0.txt"), overwrite = true)
    }
}

val licenceTexts = tasks.register<LicenceTextsTask>("generateLicenceTexts") {
    gpl.set(rootProject.layout.projectDirectory.file("LICENSE"))
    lgpl.set(project(":feature:capture").layout.projectDirectory.file("src/main/cpp/third_party/zdtun/COPYING"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(licenceTexts, LicenceTextsTask::outputDir)
    }
}
