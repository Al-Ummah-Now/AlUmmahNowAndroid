plugins {
    id("aun.android.application")
    id("aun.compose")
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "net.alummahnow.tv"
    defaultConfig {
        applicationId = "net.alummahnow.tv"
        minSdk = 26
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }
    androidResources { generateLocaleConfig = true }
    // One TV app, two store builds: Google Play (Android TV / Google TV) and Amazon Appstore (Fire TV).
    flavorDimensions += "store"
    productFlavors {
        create("google") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"google\"")
        }
        create("amazon") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"amazon\"")
        }
    }
    // Public native OAuth client "Viewer (TV), device flow" (auth migration 0013_native_clients). Not a secret.
    productFlavors {
        getByName("qa") { buildConfigField("String", "OAUTH_CLIENT_ID", "\"9c3a81c8-3f9c-4873-abb1-a51e4f1d9543\"") }
        getByName("prod") { buildConfigField("String", "OAUTH_CLIENT_ID", "\"111648c6-24e6-4c35-8bcf-f50ab5c0d96d\"") }
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
    implementation(project(":feature-tv"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.tv.material)
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
