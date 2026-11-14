plugins {
    id("aun.android.library")
    id("aun.compose")
    alias(libs.plugins.roborazzi)
}

android { namespace = "net.alummahnow.feature.passkey" }

// Native passkeys for Studio and Admin (AUTH-PASSKEYS "Native apps"): Credential Manager, the sign-in buttons, the
// in-app dialogs and their en/ar copy. Server calls live in :core-bridge (PasskeyApi / AccountSession).
dependencies {
    api(project(":core-bridge"))
    implementation(project(":design"))
    implementation(libs.androidx.credentials)
    // Passkeys on Android 9–13 (and Google Password Manager everywhere) go through Play services.
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
