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

    @Test fun `words that claim an app for children fail in both languages`() {
        val en = play(good("en-US") + good("ar") + ("listings/en-US/short-description.txt" to "Videos for Kids"))
        assertTrue(StoreListingRules.check(en).single().contains("for kids"))
        val ar = play(good("en-US") + good("ar") + ("listings/ar/short-description.txt" to "مرئيات للأطفال"))
        assertTrue(StoreListingRules.check(ar).single().contains("للأطفال"))
        val fine = play(good("en-US") + good("ar") + ("listings/ar/short-description.txt" to "أخبار الأطفال"))
        assertEquals(emptyList<String>(), StoreListingRules.check(fine))
    }

    private fun rating(json: String?): List<String> {
        val root = Files.createTempDirectory("rating").toFile()
        if (json != null) File(root, "store/play/content-rating.json").apply { parentFile.mkdirs() }.writeText(json)
        File(root, "src.kt").writeText("")
        return contentRatingProblems(root)
    }

    private fun app(answers: String = """"a":"no","targetAudience":"13-15,18+"""", source: String = "src.kt") =
        """{"answers":{$answers},"sources":["$source"]}"""

    private fun doc(viewer: String = app(), tv: String = app(), studio: String = app()) =
        """{"questions":["a","targetAudience"],"apps":{"viewer":$viewer,"viewer-tv":$tv,"studio":$studio}}"""

    @Test fun `complete content rating answers pass`() = assertEquals(emptyList<String>(), rating(doc()))

    @Test fun `content rating answers are checked`() {
        assertTrue(rating(null).single().contains("is missing"))
        assertTrue(rating("{").single().contains("not valid JSON"))
        assertTrue(rating(doc(viewer = app(""""targetAudience":"18+""""))).single().contains("viewer has no answer for a"))
        assertTrue(rating(doc(tv = app(""""a":"no","b":"x","targetAudience":"18+""""))).single().contains("answers b"))
        assertTrue(rating(doc(tv = app(""""a":"","targetAudience":"18+""""))).single().contains("empty answer for a"))
        assertTrue(rating(doc(studio = app(""""a":"no","targetAudience":"9-12,13-15""""))).single().contains("under 13"))
        assertTrue(rating(doc(studio = app(source = "gone.kt"))).single().contains("gone.kt"))
        assertTrue(rating("""{"questions":["a","targetAudience"],"apps":{"viewer":${app()}}}""").single().contains("must be exactly"))
    }
}
