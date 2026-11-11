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
        // QA seed account for the instrumented QA flow (CI passes the password from a secret; skipped when absent).
        testInstrumentationRunnerArguments["qaEmail"] = "qa-creator@delivered.resend.dev"
        System.getenv("QA_SEED_PASSWORD")?.takeIf { it.isNotBlank() }?.let { testInstrumentationRunnerArguments["qaPassword"] = it }
    }
    androidResources { generateLocaleConfig = true }
    // Public native OAuth clients ("Studio (iOS / Android)", PKCE required). Client ids are not secrets.
    productFlavors {
        getByName("qa") {
            buildConfigField("String", "AUTH_ISSUER", "\"https://auth.qa.alummahnow.net\"")
            buildConfigField("String", "AUTH_CLIENT_ID", "\"45364a42-21f4-4359-a76c-0a1e6615fd7b\"")
        }
        getByName("prod") {
            buildConfigField("String", "AUTH_ISSUER", "\"https://auth.alummahnow.net\"")
            buildConfigField("String", "AUTH_CLIENT_ID", "\"6f8073c6-754b-4d63-bf80-0546214e0d62\"")
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
    implementation(project(":feature-upload"))
    implementation(project(":feature-studio"))
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.compose)
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
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
