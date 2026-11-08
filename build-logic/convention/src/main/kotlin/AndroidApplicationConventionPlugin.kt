import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Every app gets an `env` flavour dimension: `qa` talks to *.qa.alummahnow.net, `prod` to production.
 * QA builds carry an applicationId suffix so both can be installed side by side.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = AunAndroid.TARGET_SDK
            buildFeatures.buildConfig = true
            flavorDimensions += "env"
            productFlavors {
                create("qa") {
                    dimension = "env"
                    applicationIdSuffix = ".qa"
                    versionNameSuffix = "-qa"
                    buildConfigField("String", "API_BASE_URL", "\"https://videos.api.qa.alummahnow.net\"")
                    buildConfigField("String", "WEB_BASE_URL", "\"https://videos.qa.alummahnow.net\"")
                    buildConfigField("String", "ENV_NAME", "\"qa\"")
                    buildConfigField("String", "AUTH_ISSUER", "\"https://auth.qa.alummahnow.net\"")
                }
                create("prod") {
                    dimension = "env"
                    buildConfigField("String", "API_BASE_URL", "\"https://videos.api.alummahnow.net\"")
                    buildConfigField("String", "WEB_BASE_URL", "\"https://videos.alummahnow.net\"")
                    buildConfigField("String", "ENV_NAME", "\"prod\"")
                    buildConfigField("String", "AUTH_ISSUER", "\"https://auth.alummahnow.net\"")
                }
            }
            // Upload-key signing is opt-in: only when CI provides the keystore (see docs/RELEASE.md).
            val keystorePath = System.getenv("AUN_UPLOAD_KEYSTORE_PATH")
            val uploadSigning = if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                signingConfigs.create("upload") {
                    storeFile = file(keystorePath)
                    storePassword = System.getenv("AUN_UPLOAD_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("AUN_UPLOAD_KEY_ALIAS")
                    keyPassword = System.getenv("AUN_UPLOAD_KEY_PASSWORD")
                }
            } else {
                null
            }
            buildTypes {
                getByName("release") {
                    if (uploadSigning != null) signingConfig = uploadSigning
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                }
            }
        }
    }
}
