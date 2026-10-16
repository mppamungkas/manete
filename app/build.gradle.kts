import java.util.Properties

plugins {
    alias(libs.plugins.netlogger.android.application)
    alias(libs.plugins.netlogger.android.compose)
    alias(libs.plugins.netlogger.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/** Short commit hash, "-dirty" with uncommitted changes; "unknown" without git (e.g. a source archive). */
val buildCommit: String = runCatching {
    providers.exec {
        commandLine("git", "describe", "--always", "--dirty", "--abbrev=7")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

/** local.properties (gitignored): machine-specific settings, read by the blocks below. */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}

/** A machine setting: Gradle property, else environment variable, else local.properties; null if unset. */
fun machineSetting(name: String, env: String): String? =
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(env).orNull ?: localProperties.getProperty(name))
        ?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Release signing key, never in the repo (CLAUDE.md): `netlogger.signing.storeFile`, `.storePassword`, `.keyAlias`,
 * `.keyPassword` in local.properties, as Gradle properties or as NETLOGGER_SIGNING_STORE_FILE, …_STORE_PASSWORD,
 * …_KEY_ALIAS, …_KEY_PASSWORD. Without all four (e.g. CI) the release build stays unsigned.
 */
val releaseSigning: Map<String, String>? = run {
    val values = mapOf(
        "storeFile" to machineSetting("netlogger.signing.storeFile", "NETLOGGER_SIGNING_STORE_FILE"),
        "storePassword" to machineSetting("netlogger.signing.storePassword", "NETLOGGER_SIGNING_STORE_PASSWORD"),
        "keyAlias" to machineSetting("netlogger.signing.keyAlias", "NETLOGGER_SIGNING_KEY_ALIAS"),
        "keyPassword" to machineSetting("netlogger.signing.keyPassword", "NETLOGGER_SIGNING_KEY_PASSWORD"),
    )
    if (values.values.any { it == null }) return@run null
    val complete = values.mapValues { it.value.orEmpty() }
    require(file(complete.getValue("storeFile")).isFile) { "netlogger.signing.storeFile: no file at ${complete.getValue("storeFile")}" }
    complete
}

android {
    namespace = "id.my.pamslab.netlogger"

    // Timber arrives with MapLibre and ships a lint rule against android.util.Log; NetLogger logs with Log and the
    // "NetLogger" tag on purpose (adb logcat -s NetLogger:*).
    lint.disable += "LogNotTimber"

    defaultConfig {
        applicationId = "id.my.pamslab.netlogger"
        // Raise versionCode with every APK given to users: Android installs an update only over a lower one.
        versionCode = 2
        versionName = "0.2.0"
        // Settings › About shows the commit the APK was built from (manifest meta-data, readable by every module).
        manifestPlaceholders["buildCommit"] = buildCommit
    }

    signingConfigs {
        releaseSigning?.let { key ->
            create("release") {
                storeFile = file(key.getValue("storeFile"))
                storePassword = key.getValue("storePassword")
                keyAlias = key.getValue("keyAlias")
                keyPassword = key.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Own app ID, so a debug build installs next to the release APK on one phone, each with its own data.
            // adb: id.my.pamslab.netlogger.debug/id.my.pamslab.netlogger.MainActivity (classes keep their names).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Not yet: R8 needs keep rules checked on both phones (Room, Hilt, JNI of libnetcapture).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Phones only: 64- and 32-bit ARM. The emulator ABIs (x86, x86_64) would add ~27 MB of native libraries.
            ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
}

/**
 * Plain-HTTP hosts (e.g. a LibreSpeed speed test server without TLS), allowed by the generated network security
 * config; every other host is HTTPS only. Set per machine, never in the repo: `netlogger.cleartextHosts` in
 * local.properties (gitignored) or as a Gradle property, or NETLOGGER_CLEARTEXT_HOSTS; comma separated host names or
 * IPv4 addresses. Unset (e.g. CI): no cleartext at all.
 */
val cleartextHosts: List<String> = run {
    val raw = machineSetting("netlogger.cleartextHosts", "NETLOGGER_CLEARTEXT_HOSTS") ?: ""
    raw.split(',').map(String::trim).filter(String::isNotEmpty).onEach {
        require(Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$").matches(it)) {
            "netlogger.cleartextHosts: \"$it\" is not a host name or IPv4 address"
        }
    }
}

/** Writes res/xml/network_security_config.xml: cleartext off, except for [hosts] (exact host, no subdomains). */
abstract class NetworkSecurityConfigTask : DefaultTask() {
    @get:Input abstract val hosts: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val domains = hosts.get().joinToString("") { "            <domain includeSubdomains=\"false\">$it</domain>\n" }
        val exceptions = if (domains.isEmpty()) "" else
            "    <!-- Plain HTTP allowed only for these hosts (netlogger.cleartextHosts). -->\n" +
                "    <domain-config cleartextTrafficPermitted=\"true\">\n$domains    </domain-config>\n"
        val file = outputDir.get().file("xml/network_security_config.xml").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<!-- Generated by :app:generateNetworkSecurityConfig (app/build.gradle.kts). Do not edit. -->\n" +
                "<network-security-config>\n" +
                "    <base-config cleartextTrafficPermitted=\"false\" />\n" +
                exceptions +
                "</network-security-config>\n",
        )
    }
}

val networkSecurityConfig = tasks.register<NetworkSecurityConfigTask>("generateNetworkSecurityConfig") {
    hosts.set(cleartextHosts)
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(networkSecurityConfig, NetworkSecurityConfigTask::outputDir)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:data"))
    implementation(project(":feature:logger"))
    implementation(project(":feature:map"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:speedtest"))
    implementation(project(":feature:capture"))

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
