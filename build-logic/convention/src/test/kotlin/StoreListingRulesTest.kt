import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreListingRulesTest {
    private fun play(files: Map<String, String>): File {
        val root = Files.createTempDirectory("listing").toFile()
        val play = File(root, "app/src/main/play")
        files.forEach { (path, text) -> File(play, path).apply { parentFile.mkdirs() }.writeText(text) }
        return play
    }

    private fun good(locale: String) = mapOf(
        "listings/$locale/title.txt" to "Al Ummah Now",
        "listings/$locale/short-description.txt" to "Watch videos.",
        "listings/$locale/full-description.txt" to "Videos, Shorts and live streams.",
        "release-notes/$locale/internal.txt" to "Fixes."
    )

    @Test fun `a complete en and ar listing passes`() {
        assertEquals(emptyList<String>(), StoreListingRules.check(play(good("en-US") + good("ar"))))
    }

    @Test fun `limits are enforced`() {
        val p = play(good("en-US") + good("ar") + ("listings/ar/title.txt" to "ا".repeat(31)))
        assertTrue(StoreListingRules.check(p).single().contains("31 characters, Play allows 30"))
    }

    @Test fun `en and ar must match`() {
        val p = play(good("en-US") + good("ar") + ("listings/en-US/video-url.txt" to "https://example.com/v"))
        assertTrue(StoreListingRules.check(p).any { it.contains("same files") })
    }

    @Test fun `a missing locale fails`() {
        assertTrue(StoreListingRules.check(play(good("en-US"))).any { it.contains("must be exactly") })
    }

    @Test fun `copy tone words fail`() {
        val p = play(good("en-US") + good("ar") + ("listings/ar/full-description.txt" to "مكتبة من الفيديوهات"))
        val problems = StoreListingRules.check(p)
        assertTrue(problems.any { it.contains("مكتبة") })
        assertTrue(problems.any { it.contains("فيديو") })
    }

    @Test fun `release notes over 500 characters fail`() {
        val p = play(good("en-US") + good("ar") + ("release-notes/ar/internal.txt" to "x".repeat(501)))
        assertTrue(StoreListingRules.check(p).single().contains("501 characters"))
    }
}
