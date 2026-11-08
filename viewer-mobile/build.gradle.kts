plugins {
    id("aun.android.application")
    id("aun.compose")
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "net.alummahnow.viewer"
    defaultConfig {
        applicationId = "net.alummahnow.app"
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }
    androidResources { generateLocaleConfig = true }
    // Public native OAuth client "Viewer (iOS / Android)" (auth migration 0013_native_clients). Not a secret.
    productFlavors {
        getByName("qa") { buildConfigField("String", "OAUTH_CLIENT_ID", "\"31389afb-d4f8-4c26-9492-4c77f4d07e65\"") }
        getByName("prod") { buildConfigField("String", "OAUTH_CLIENT_ID", "\"567a2cbc-dab8-4d5c-9437-a9e61c7120f5\"") }
    }
}

play {
    // Credentials come from ANDROID_PUBLISHER_CREDENTIALS (CI secret). Never committed.
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":design"))
    implementation(project(":player"))
    implementation(project(":feature-home"))
    implementation(project(":feature-watch"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.media3.session)
    implementation(libs.media3.cast)
    implementation(libs.compose.material3.window.size)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.guava)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
