package net.alummahnow.admin

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media rule, checked in the source: nothing in the Admin app (or the admin bridge) may use a media cache,
 * downloads, the media library, sharing or a file for media. A new use fails the build's tests.
 */
class MediaRuleSourceTest {
    private val forbidden = listOf(
        "SimpleCache", "CacheDataSource", "DownloadManager", "DownloadService", "DownloadHelper", "MediaStore",
        "ACTION_SEND", "Intent.createChooser", "FileProvider", "diskCache(DiskCache", "okhttp3.Cache(", "Cache(File",
        "openFileOutput", "createTempFile", "getExternalStorage"
    )

    private fun sources(): List<File> {
        val module = File(".").absoluteFile.let { if (File(it, "src/main").exists()) it else File(it, "admin-mobile") }
        val bridge = File(module, "../core-bridge/src/main/kotlin/net/alummahnow/core/bridge/admin")
        return (File(module, "src/main").walkTopDown() + bridge.walkTopDown()).filter {
            it.isFile &&
                (it.extension == "kt" || it.extension == "xml")
        }.toList()
    }

    @Test fun `no media cache, download, share or file write anywhere in the Admin app`() {
        val files = sources()
        assertTrue("found the sources", files.size > 10)
        val hits = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                forbidden.firstOrNull { code.contains(it) }?.let { "${f.name}:${i + 1} uses $it" }
            }
        }
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun `no backup and no cleartext in the manifest`() {
        val manifest = sources().first { it.name == "AndroidManifest.xml" }.readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(!manifest.contains("WRITE_EXTERNAL_STORAGE") && !manifest.contains("READ_MEDIA"))
    }
}
