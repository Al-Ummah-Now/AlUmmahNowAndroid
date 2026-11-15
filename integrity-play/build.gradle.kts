plugins {
    id("aun.android.library")
}

android { namespace = "net.alummahnow.integrity" }

// Play Integrity (standard requests) behind :core-bridge's IntegrityTokenSource, for the Google Play builds of Studio
// and Admin (NAT-REL contract §2). Amazon / TV builds never depend on this module.
dependencies {
    api(project(":core-bridge"))
    implementation(libs.play.integrity)
    implementation(libs.kotlinx.coroutines.android)
}
