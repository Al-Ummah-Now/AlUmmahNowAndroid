import java.util.zip.ZipFile

plugins {
    id("aun.android.application")
    id("aun.compose")
}

android {
    namespace = "net.alummahnow.viewer.instant"
    defaultConfig {
        // Google Play Instant shares the installed viewer's applicationId (`net.alummahnow.app`) with a lower
        // versionCode ceiling than the installed app, and stays <= 15 MB (`E22-D8`).
        applicationId = "net.alummahnow.app"
        versionCode = (project.findProperty("instantVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }
    androidResources { generateLocaleConfig = true }
    packaging {
        jniLibs {
            // The instant player resolves videos over OkHttp + ExoPlayer and never loads UniFFI/JNA native libs.
            excludes += listOf("**/libaun_core.so", "**/libjnidispatch.so")
        }
    }
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":design"))
    implementation(project(":player"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)
    testImplementation(libs.junit)
}

tasks.register("checkInstantSize") {
    group = "verification"
    description = "Verifies that the R8-minified Google Play Instant single-ABI download stays <= 15 MB (E22-D8)."
    dependsOn("bundleProdRelease")
    val bundleDirProvider = layout.buildDirectory.dir("outputs/bundle/prodRelease")
    doLast {
        val maxBytes = 15_000_000L
        val bundleDir = bundleDirProvider.get().asFile
        val aab = bundleDir.walkTopDown().firstOrNull { it.extension == "aab" }
            ?: throw GradleException("No prodRelease AAB found in $bundleDir")
        val abiRegex = Regex("^[^/]+/lib/([^/]+)/.+")
        var sharedCompressedBytes = 0L
        val perAbiCompressedBytes = mutableMapOf<String, Long>()
        ZipFile(aab).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.name.startsWith("BUNDLE-METADATA/") || entry.name.startsWith("META-INF/")) continue
                val compressed = if (entry.compressedSize >= 0) entry.compressedSize else entry.size
                val abiMatch = abiRegex.matchEntire(entry.name)
                if (abiMatch != null) {
                    val abi = abiMatch.groupValues[1]
                    perAbiCompressedBytes[abi] = (perAbiCompressedBytes[abi] ?: 0L) + compressed
                } else {
                    sharedCompressedBytes += compressed
                }
            }
        }
        val worstAbiBytes = perAbiCompressedBytes.values.maxOrNull() ?: 0L
        val totalDownloadBytes = sharedCompressedBytes + worstAbiBytes
        logger.lifecycle("viewer-instant single-ABI download size: $totalDownloadBytes bytes (budget: $maxBytes bytes)")
        if (totalDownloadBytes > maxBytes) {
            throw GradleException(
                "viewer-instant download size ($totalDownloadBytes bytes) exceeds 15 MB Play Instant cap ($maxBytes bytes)"
            )
        }
    }
}
