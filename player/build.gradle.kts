plugins {
    id("aun.android.library")
    id("aun.compose")
}

android { namespace = "net.alummahnow.player" }

dependencies {
    api(project(":core-bridge"))
    implementation(project(":design"))
    api(libs.media3.exoplayer)
    api(libs.media3.session)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui)
    implementation(libs.media3.cast)
    implementation(libs.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.media3.test.utils)
}
