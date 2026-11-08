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
        // aun-core (Rust core, UniFFI AAR) from AlUmmahNowCore releases. GitHub Packages needs a token even
        // for public packages: CI passes GITHUB_TOKEN; locally set aunCoreUsername / aunCorePassword (a PAT
        // with read:packages) in ~/.gradle/gradle.properties, or point aunCoreLocalRepo at a local Maven repo.
        val localCore = providers.gradleProperty("aunCoreLocalRepo").orNull
        if (localCore != null) {
            maven(localCore) { content { includeGroup("net.alummahnow") } }
        } else {
            maven("https://maven.pkg.github.com/Al-Ummah-Now/AlUmmahNowCore") {
                name = "aunCore"
                credentials(PasswordCredentials::class)
                content { includeGroup("net.alummahnow") }
            }
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
include(":feature-editor")
include(":feature-live")

// Apps
include(":viewer-mobile")
include(":viewer-tv")
include(":studio-mobile")
