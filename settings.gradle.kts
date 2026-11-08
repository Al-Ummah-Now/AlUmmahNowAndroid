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

rootProject.name = "AlUmmahNowAndroid"

// Shared libraries
include(":core-bridge")
include(":design")
include(":player")

// Features
include(":feature-home")
include(":feature-watch")
include(":feature-tv")
include(":feature-upload")
include(":feature-editor")
include(":feature-live")

// Apps
include(":viewer-mobile")
include(":viewer-tv")
include(":studio-mobile")
