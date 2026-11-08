plugins {
    id("aun.android.application")
    id("aun.compose")
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "net.alummahnow.studio"
    defaultConfig {
        applicationId = "net.alummahnow.studio"
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }
    androidResources { generateLocaleConfig = true }
    // Public native OAuth client "Studio (iOS / Android)" (auth migration 0013_native_clients). Not a secret.
    productFlavors {
        getByName("qa") { buildConfigField("String", "OAUTH_CLIENT_ID", "\"45364a42-21f4-4359-a76c-0a1e6615fd7b\"") }
        getByName("prod") { buildConfigField("String", "OAUTH_CLIENT_ID", "\"6f8073c6-754b-4d63-bf80-0546214e0d62\"") }
    }
}

play {
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":design"))
    implementation(project(":feature-upload"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-live"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
