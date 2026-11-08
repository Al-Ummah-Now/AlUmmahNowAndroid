plugins {
    id("aun.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "net.alummahnow.core.bridge" }

dependencies {
    api(libs.kotlinx.coroutines.core)
    // Rust core (UniFFI). Exposed as api: apps implement its platform adapters (SecureStorage, Connectivity).
    api(libs.aun.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.tus.client)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
