pluginManagement {
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

rootProject.name = "aether"

// Pure Kotlin: no Android APIs (docs/product.md §3).
include(":ytmusic")
include(":core:model")
include(":core:lyrics")
include(":core:transfer")
include(":core:automix")
include(":sources:youtube")
include(":sources:local")
include(":sources:addon")
include(":sources:apple")
include(":sources:canvas")

// Android
include(":core:data")
include(":core:designsystem")
include(":player")
include(":app")
