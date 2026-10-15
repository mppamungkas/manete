pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "netlogger"

include(":app")

include(":core:model")
include(":core:telephony")
include(":core:location")
include(":core:device")
include(":core:data")
include(":core:export")
include(":core:ui")

include(":feature:logger")
include(":feature:map")
include(":feature:sessions")
include(":feature:settings")
include(":feature:speedtest")
include(":feature:capture")
