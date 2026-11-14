plugins {
    id("aun.android.application")
    id("aun.compose")
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.play.publisher)
}

// Firebase (FCM) config is owner-supplied and never committed (.gitignore). Without it the app builds and runs with
// push off and says so in Settings; with it (CI writes it from a secret), the Google services plugin is applied.
val firebaseConfigured = listOf("google-services.json", "src/qa/google-services.json", "src/prod/google-services.json")
    .any { file(it).exists() }
if (firebaseConfigured) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "net.alummahnow.admin"
    defaultConfig {
        // Internal app (AUN-ADMIN-NATIVE AD10): Play internal testing only. No GitHub Releases, no public APK.
        applicationId = "net.alummahnow.admin"
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
    }
    androidResources { generateLocaleConfig = true }
    // Public native OAuth clients "Admin (Android)" (PKCE required; AlUmmahNowAuth migration 0037). Client ids are
    // not secrets. Redirect alummahnow-admin://auth/callback.
    productFlavors {
        getByName("qa") {
            buildConfigField("String", "AUTH_ISSUER", "\"https://auth.qa.alummahnow.net\"")
            buildConfigField("String", "AUTH_CLIENT_ID", "\"5b44a4f7-d369-4d12-bc69-028d0276604a\"")
            buildConfigField("String", "ADMIN_API_BASE_URL", "\"https://admin.api.qa.alummahnow.net\"")
        }
        getByName("prod") {
            buildConfigField("String", "AUTH_ISSUER", "\"https://auth.alummahnow.net\"")
            buildConfigField("String", "AUTH_CLIENT_ID", "\"6634c4c9-a80d-4961-9427-d997349f8ea2\"")
            buildConfigField("String", "ADMIN_API_BASE_URL", "\"https://admin.api.alummahnow.net\"")
        }
    }
}

play {
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":design"))
    implementation(project(":player"))
    implementation(project(":feature-passkey"))
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.fragment)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
