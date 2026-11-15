package net.alummahnow.core.bridge.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkParserTest {
    private val host = "videos.qa.alummahnow.net"
    private fun parse(path: String) = AppLinkParser.parse("https://$host$path", host)

    @Test fun `video in en, ar and without a locale`() {
        assertEquals(AppLink.Video("v1", "en"), parse("/en/video/v1"))
        assertEquals(AppLink.Video("v1", "ar"), parse("/ar/video/v1?t=30"))
        assertEquals(AppLink.Video("v1"), parse("/video/v1"))
    }

    @Test fun `shorts, playlist and post`() {
        assertEquals(AppLink.Short("s1", "ar"), parse("/ar/shorts/s1"))
        assertEquals(AppLink.Short("s1"), parse("/shorts/s1/"))
        assertEquals(AppLink.Playlist("p1", "en"), parse("/en/playlist/p1"))
        assertEquals(AppLink.Post("x1", "ar"), parse("/ar/post/x1"))
        assertEquals(AppLink.Post("x1"), parse("/post/x1"))
    }

    @Test fun `channel handles, Latin and percent-encoded Arabic, with or without a tab`() {
        assertEquals(AppLink.Channel("alummah", "en"), parse("/en/@alummah"))
        assertEquals(AppLink.Channel("alummah"), parse("/@alummah/videos"))
        assertEquals(AppLink.Channel("قناة", "ar"), parse("/ar/@%D9%82%D9%86%D8%A7%D8%A9"))
        assertNull(parse("/en/@"))
    }

    @Test fun `auth callback keeps the raw query`() {
        assertEquals(AppLink.AuthCallback("code=abc&state=xyz"), parse("/app/auth/callback?code=abc&state=xyz"))
        assertEquals(AppLink.AuthCallback(null), parse("/app/auth/callback"))
        assertNull(parse("/app/auth/callback/extra"))
        assertNull(parse("/en/app/auth/callback"))
    }

    @Test fun `studio routes map to channel, section and video`() {
        assertEquals(AppLink.Studio(), parse("/app/studio"))
        assertEquals(AppLink.Studio("ch1", StudioSection.DASHBOARD), parse("/app/studio/ch1"))
        assertEquals(AppLink.Studio("ch1", StudioSection.DASHBOARD, lang = "en"), parse("/en/studio/ch1/dashboard"))
        assertEquals(AppLink.Studio("ch1", StudioSection.ANALYTICS, lang = "ar"), parse("/ar/studio/ch1/analytics"))
        assertEquals(AppLink.Studio("ch1", StudioSection.CONTENT, lang = "en"), parse("/en/studio/ch1/content/videos"))
        assertEquals(AppLink.Studio("ch1", StudioSection.CONTENT, "v9", "ar"), parse("/ar/studio/ch1/content/videos/v9/edit"))
        assertEquals(AppLink.Studio(videoId = "v9", lang = "en"), parse("/en/studio/video/v9/edit"))
        assertEquals(AppLink.Studio(lang = "en"), parse("/en/studio/go/upload"))
        assertEquals(AppLink.Studio("ch1", lang = "en"), parse("/en/studio/ch1/site"))
    }

    @Test fun `studio is only claimed with a locale or under app`() {
        assertNull(parse("/studio/ch1"))
    }

    @Test fun `other hosts, http, unknown paths and junk are ignored`() {
        assertNull(AppLinkParser.parse("https://videos.alummahnow.net/en/video/v1", host))
        assertNull(AppLinkParser.parse("http://$host/en/video/v1", host))
        assertNull(AppLinkParser.parse("https://evil.example/$host/en/video/v1", host))
        assertNull(parse("/en/video"))
        assertNull(parse("/en/library"))
        assertNull(parse("/"))
        assertNull(AppLinkParser.parse("not a url ::", host))
    }

    @Test fun `host match ignores case`() {
        assertEquals(AppLink.Video("v1", "en"), AppLinkParser.parse("https://VIDEOS.QA.alummahnow.net/en/video/v1", host))
    }

    @Test fun `custom scheme watch links still work`() {
        assertEquals(AppLink.Video("v1"), AppLinkParser.parse("alummahnow://watch/v1", host))
        assertNull(AppLinkParser.parse("alummahnow://other/v1", host))
    }

    @Test fun `ids that could forge an in-app route are refused`() {
        assertNull(parse("/en/video/a%2Fb"))
        assertNull(parse("/en/video/a%3Fb=1"))
        assertNull(parse("/shorts/%25"))
        assertNull(AppLinkParser.parse("alummahnow://watch/a%2F..", host))
        assertNull(parse("/en/@a%2Fb"))
        assertEquals(AppLink.Studio(lang = "en"), parse("/en/studio/a%2Fb/content"))
        assertEquals(AppLink.Studio("ch1", StudioSection.CONTENT, lang = "en"), parse("/en/studio/ch1/content/videos/x%3Fy"))
    }
}
