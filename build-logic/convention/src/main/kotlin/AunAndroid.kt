import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/** Shared Android settings for every module. Kept in one place so SDK levels never drift. */
object AunAndroid {
    const val COMPILE_SDK = 37
    const val MIN_SDK = 26
    const val TARGET_SDK = 37
}

internal fun Project.configureAndroidCommon(ext: CommonExtension) {
    ext.compileSdk = AunAndroid.COMPILE_SDK
    ext.defaultConfig.minSdk = AunAndroid.MIN_SDK
    ext.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ext.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    ext.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    ext.testOptions.unitTests.isIncludeAndroidResources = true
    // `-PaunStoreScreenshots=true` turns on the store screenshot generators (StoreScreenshots tests; off otherwise).
    val storeScreenshots = providers.gradleProperty("aunStoreScreenshots").orElse("false")
    ext.testOptions.unitTests.all {
        it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        it.systemProperty("aun.storeScreenshots", storeScreenshots.get())
    }
    ext.lint.warningsAsErrors = false
    ext.lint.abortOnError = true
    ext.lint.checkDependencies = false
    ext.lint.disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    ext.lint.xmlReport = true
    ext.lint.sarifReport = true
    ext.packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
}
