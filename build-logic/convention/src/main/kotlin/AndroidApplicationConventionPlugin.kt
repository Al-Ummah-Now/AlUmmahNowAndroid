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
                }
                create("prod") {
                    dimension = "env"
                    buildConfigField("String", "API_BASE_URL", "\"https://videos.api.alummahnow.net\"")
                    buildConfigField("String", "WEB_BASE_URL", "\"https://videos.alummahnow.net\"")
                    buildConfigField("String", "ENV_NAME", "\"prod\"")
                }
            }
            // Release signing, in order: the owner's upload key (docs/RELEASE.md), else the pre-release
            // key CI keeps for GitHub pre-releases (installable, updatable, never accepted by Play), else
            // unsigned. Both come only from CI environment variables; nothing is in the repo.
            fun keystore(prefix: String, name: String) = System.getenv("${prefix}_KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() && file(it).exists() }
                ?.let { path ->
                    signingConfigs.create(name) {
                        storeFile = file(path)
                        storePassword = System.getenv("${prefix}_KEYSTORE_PASSWORD")
                        keyAlias = System.getenv("${prefix}_KEY_ALIAS")
                        keyPassword = System.getenv("${prefix}_KEY_PASSWORD")
                    }
                }
            val releaseSigning = keystore("AUN_UPLOAD", "upload") ?: keystore("AUN_PRERELEASE", "prerelease")
                // CI test artifacts: `-PaunDebugSignRelease=true` signs release builds with the debug key.
                ?: signingConfigs.getByName("debug").takeIf { providers.gradleProperty("aunDebugSignRelease").orNull == "true" }
            buildTypes {
                getByName("release") {
                    if (releaseSigning != null) signingConfig = releaseSigning
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                }
            }
        }
    }
}
