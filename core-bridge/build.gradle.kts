plugins {
    id("aun.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "net.alummahnow.core.bridge" }

// `-PaunCoreAar=/path/aun-core-release.aar` builds against a local or CI-artifact AAR (for example
// an AlUmmahNowCore PR build) instead of the released one. Local only: never commit it.
val localCoreAar: String? = providers.gradleProperty("aunCoreAar").orNull

dependencies {
    api(libs.kotlinx.coroutines.core)
    if (localCoreAar != null) {
        api(files(localCoreAar))
    } else {
        api("net.alummahnow:aun-core:${libs.versions.aunCore.get()}@aar")
    }
    // UniFFI's runtime; the AAR from the GitHub release carries no POM, so it is declared here.
    implementation(libs.jna.aar) { artifact { type = "aar" } }
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.tus.client)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}
