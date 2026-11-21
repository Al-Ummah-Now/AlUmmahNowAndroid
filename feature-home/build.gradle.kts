plugins {
    id("aun.android.library")
    id("aun.compose")
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "net.alummahnow.feature.home"
    // The brand channel's screenshot fixtures: one source of videos, views and dates for every module that draws them.
    sourceSets.getByName("test").kotlin.directories.add("../core-bridge/src/test/fixtures")
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":design"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.adaptive)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.coil.compose)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
