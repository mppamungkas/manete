import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin modules (no Android dependencies), unit-testable on the JVM. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()
        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
        }
        // `./gradlew testDebugUnitTest` (CLAUDE.md, CI) runs the Android modules' unit tests; this alias makes it run the
        // JVM modules' `test` too.
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Runs this JVM module's unit tests (alias of test)."
            dependsOn("test")
        }
    }
}
