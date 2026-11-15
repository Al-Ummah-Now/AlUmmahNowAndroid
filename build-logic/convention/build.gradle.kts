plugins {
    `kotlin-dsl`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "aun.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "aun.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("storeListings") {
            id = "aun.store-listings"
            implementationClass = "StoreListingsPlugin"
        }
        register("compose") {
            id = "aun.compose"
            implementationClass = "ComposeConventionPlugin"
        }
    }
}
