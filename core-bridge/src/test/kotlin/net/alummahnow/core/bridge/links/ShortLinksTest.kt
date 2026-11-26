package net.alummahnow.core.bridge.links

import net.alummahnow.core.bridge.links.ShortLinks.Kind
import net.alummahnow.core.bridge.links.ShortLinks.PublicPlaylist
import net.alummahnow.core.bridge.links.ShortLinks.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortLinksTest {
    private val site = "https://videos.alummahnow.net"
    private val fallback = "$site/en/video/abc"

    @Test
    fun routesAndLanguage() {
        val routes = mapOf(
            Target(Kind.VIDEO, "abc") to "/v/abc",
            Target(Kind.SHORT, "abc") to "/s/abc",
            Target.channel("abc", "@a.b") to "/@a.b",
            Target.channel("abc", null) to "/c/abc",
            Target(Kind.POST, "abc") to "/p/abc",
            Target(Kind.PLAYLIST, "abc") to "/l/abc"
        )
        routes.forEach { (target, path) ->
            val expected = "https://alummahnow.link$path"
            assertEquals(expected, link(target))
            assertEquals("$expected?lang=ar", link(target, language = "ar"))
            assertNull(ShortLinks.shareUrl(target, site, fallback, "en", isPrivate = true))
        }
    }

    @Test
    fun videoOptions() {
        val video = Target(Kind.VIDEO, "abc")
        val base = "https://alummahnow.link/v/abc"
        assertEquals("$base?t=12&list=p_1&index=0&lang=ar", link(video, "ar", 12, PublicPlaylist("p_1", 0)))
        assertEquals("$base?list=p_1", link(video, seconds = 0, playlist = PublicPlaylist("p_1", -1)))
        assertEquals(base, link(video, seconds = -1, playlist = PublicPlaylist("_invalid", 4)))
        assertEquals("https://alummahnow.link/s/abc", link(Target(Kind.SHORT, "abc"), seconds = 5, playlist = PublicPlaylist("p_1")))
    }

    @Test
    fun environmentHosts() {
        assertEquals("https://alummahnow.link", ShortLinks.shortLinkOriginFor("https://VIDEOS.API.ALUMMAHNOW.NET:443/path"))
        assertEquals("https://stage.alummahnow.link", ShortLinks.shortLinkOriginFor("videos.api.stage.alummahnow.net"))
        assertEquals("https://qa.alummahnow.link", ShortLinks.shortLinkOriginFor("videos.qa.alummahnow.net"))
        assertEquals("https://qa.alummahnow.link", ShortLinks.shortLinkOriginFor("qa.alummahnow.link"))
        listOf("http://localhost:3000", "videos.pr-42.alummahnow.net", "videos.dev.alummahnow.net").forEach {
            assertNull(ShortLinks.shortLinkOriginFor(it))
            assertEquals(fallback, ShortLinks.shareUrl(Target(Kind.VIDEO, "abc"), it, fallback, "en", isPrivate = false))
        }
    }

    @Test
    fun invalidIdsAndHandlesUseFullPage() {
        listOf("", "_abc", "-abc", "x/y", "a".repeat(65)).forEach {
            assertEquals(fallback, link(Target(Kind.VIDEO, it)))
        }
        listOf("ab", ".abc", "abc_", "العربية", "a".repeat(31)).forEach {
            assertEquals(fallback, link(Target.channel("abc", it)))
        }
    }

    private fun link(target: Target, language: String = "en", seconds: Long? = null, playlist: PublicPlaylist? = null) =
        ShortLinks.shareUrl(target, site, fallback, language, isPrivate = false, startSeconds = seconds, playlist = playlist)
}
