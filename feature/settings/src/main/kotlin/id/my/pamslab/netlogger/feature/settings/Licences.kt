package id.my.pamslab.netlogger.feature.settings

import androidx.annotation.RawRes

/**
 * Licence texts shown in Settings › About. GPL and LGPL are copied from the repo at build time
 * (feature/settings/build.gradle.kts); the others are in res/raw.
 */
internal enum class Licence(val title: String, @RawRes val raw: Int) {
    GPL_3("GNU General Public License v3", R.raw.licence_gpl_3_0),
    LGPL_3("GNU Lesser General Public License v3", R.raw.licence_lgpl_3_0),
    APACHE_2("Apache License 2.0", R.raw.licence_apache_2_0),
    BSD_MAPLIBRE_NATIVE("BSD 2-Clause (MapLibre Native)", R.raw.licence_maplibre_native),
    BSD_MAPLIBRE_GESTURES("BSD 2-Clause (MapLibre Gestures)", R.raw.licence_maplibre_gestures),
    BSD_UTHASH("BSD 1-Clause (uthash)", R.raw.licence_uthash),
}

/**
 * One credited project. Names, holders and licence names are proper names, so they are not in strings.xml.
 * [licence] null: not open source, [note] says where its terms are.
 */
internal data class Credit(val name: String, val holder: String, val licenceName: String, val use: String, val licence: Licence?, val note: String? = null)

/**
 * Code copied into NetLogger (docs/third-party.md) and the libraries in the APK (app's release runtime classpath,
 * licences as declared in each library's POM; checked 2026-10-15). Update together with gradle/libs.versions.toml.
 */
internal val CREDITS = listOf(
    Credit("zdtun", "Emanuele Faranda", "LGPL v3 or later", "Traffic capture forwarder, copied unchanged", Licence.LGPL_3),
    Credit("uthash", "Troy D. Hanson", "BSD 1-Clause", "Hash tables inside zdtun", Licence.BSD_UTHASH),
    Credit("NetMonster Core", "Michal Mroček", "Apache 2.0", "Samsung cell data corrections, adapted", Licence.APACHE_2),
    Credit("MapLibre Native for Android", "MapLibre contributors, MapTiler.com, Mapbox", "BSD 2-Clause", "Map", Licence.BSD_MAPLIBRE_NATIVE),
    Credit("MapLibre Gestures for Android", "Mapbox, MapLibre contributors", "BSD 2-Clause", "Map gestures", Licence.BSD_MAPLIBRE_GESTURES),
    Credit(
        "Android Jetpack (AndroidX): Compose, Material 3, Room, Navigation, Lifecycle, Activity, Core",
        "The Android Open Source Project", "Apache 2.0", "User interface, database", Licence.APACHE_2,
    ),
    Credit("Dagger and Hilt", "The Dagger Authors", "Apache 2.0", "Dependency injection", Licence.APACHE_2),
    Credit("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o. and contributors", "Apache 2.0", "Language and libraries", Licence.APACHE_2),
    Credit("OkHttp and Okio", "Square, Inc.", "Apache 2.0", "Speed test", Licence.APACHE_2),
    Credit("Gson, Guava (ListenableFuture), JSR 305 annotations", "Google LLC and others", "Apache 2.0", "Used by MapLibre and AndroidX", Licence.APACHE_2),
    Credit("Timber", "Jake Wharton", "Apache 2.0", "Used by MapLibre", Licence.APACHE_2),
    Credit("javax.inject, Jakarta Inject, JSpecify", "The JSR-330 Expert Group, Eclipse Foundation, JSpecify authors", "Apache 2.0", "Annotations", Licence.APACHE_2),
    Credit(
        "Google Play services location", "Google LLC", "Android Software Development Kit License", "Location (fused location provider)", null,
        note = "Not open source: developer.android.com/studio/terms",
    ),
)

/** Where the source code is published (GPL v3: recipients may get the source). */
internal const val SOURCE_URL = "https://github.com/mppamungkas/netlogger"
