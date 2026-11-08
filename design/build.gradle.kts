plugins {
    id("aun.android.library")
    id("aun.compose")
    alias(libs.plugins.roborazzi)
}

android { namespace = "net.alummahnow.design" }

dependencies {
    api(libs.compose.material3)
    api(libs.compose.foundation)
    api(libs.compose.material.icons)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
}
