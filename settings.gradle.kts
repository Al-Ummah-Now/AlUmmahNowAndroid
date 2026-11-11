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
        // aun-core (Rust core + UniFFI Kotlin) AAR, straight from the public AlUmmahNowCore GitHub
        // release, so builds need no GitHub Packages token. Version: `aunCore` in libs.versions.toml.
        ivy("https://github.com/Al-Ummah-Now/AlUmmahNowCore/releases/download") {
            name = "AlUmmahNowCoreReleases"
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("net.alummahnow", "aun-core") }
        }
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
include(":feature-studio")
include(":feature-editor")
include(":feature-live")

// Apps
include(":viewer-mobile")
include(":viewer-tv")
include(":studio-mobile")
