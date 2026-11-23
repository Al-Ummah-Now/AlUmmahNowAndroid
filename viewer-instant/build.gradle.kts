plugins {
    id("aun.android.application")
    id("aun.compose")
}

android {
    namespace = "net.alummahnow.viewer.instant"
    defaultConfig {
        // Google Play Instant shares the installed viewer's applicationId (`net.alummahnow.app`) with a lower
        // versionCode ceiling than the installed app, and stays <= 15 MB (`E22-D8`).
        applicationId = "net.alummahnow.app"
        versionCode = (project.findProperty("instantVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }
    androidResources { generateLocaleConfig = true }
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":design"))
    implementation(project(":player"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)
    testImplementation(libs.junit)
}
