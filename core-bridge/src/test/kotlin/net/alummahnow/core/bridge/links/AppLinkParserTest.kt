package net.alummahnow.core.bridge.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkParserTest {
    private val host = "videos.qa.alummahnow.net"
    private val shortHost = "qa.alummahnow.link"
    private fun parse(path: String) = AppLinkParser.parse("https://$host$path", host, shortHost)

    @Test fun `video in en, ar and without a locale`() {
        assertEquals(AppLink.Video("v1", "en"), parse("/en/video/v1"))
        assertEquals(AppLink.Video("v1", "ar"), parse("/ar/video/v1?t=30"))
        assertEquals(AppLink.Video("v1"), parse("/video/v1"))
    }

    @Test fun `shorts, live, playlist and post`() {
        assertEquals(AppLink.Short("s1", "ar"), parse("/ar/shorts/s1"))
        assertEquals(AppLink.Short("s1"), parse("/shorts/s1/"))
        assertEquals(AppLink.Live("str1", "en"), parse("/en/live/str1"))
        assertEquals(AppLink.Live("str1"), parse("/live/str1"))
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

    @Test fun `short links build and parse cleanly across video, short, live, channel, post and playlist`() {
        val videoUrl = ShortLinks.video("vid_123", shortHost, startSeconds = 95, playlistId = "pl_1", playlistIndex = 2, lang = "ar")
        assertEquals("https://qa.alummahnow.link/v/vid_123?t=95&list=pl_1&index=2&lang=ar", videoUrl)
        val inv = AppLinkParser.parseInvocation(videoUrl!!, host, shortHost)
        assertEquals(AppLink.Video("vid_123", "ar"), inv?.link)
        assertEquals(95L, inv?.startSeconds)
        assertEquals("pl_1", inv?.playlistId)
        assertEquals(2, inv?.playlistIndex)
        assertEquals("ar", inv?.lang)

        assertEquals(
            AppLink.Short("sh_9"),
            AppLinkParser.parse(ShortLinks.short("sh_9", shortHost)!!, host, shortHost)
        )
        assertEquals(
            AppLink.Live("str_5", "ar"),
            AppLinkParser.parse(ShortLinks.live("str_5", shortHost, lang = "ar")!!, host, shortHost)
        )
        assertEquals(
            AppLink.Channel("الأمة", "ar"),
            AppLinkParser.parse(ShortLinks.channelHandle("@الأمة", shortHost, lang = "ar")!!, host, shortHost)
        )
        assertEquals(
            AppLink.ChannelId("ch_42"),
            AppLinkParser.parse(ShortLinks.channelId("ch_42", shortHost)!!, host, shortHost)
        )
        assertEquals(
            AppLink.Post("po_7"),
            AppLinkParser.parse(ShortLinks.post("po_7", shortHost)!!, host, shortHost)
        )
        assertEquals(
            AppLink.Playlist("pl_8"),
            AppLinkParser.parse(ShortLinks.playlist("pl_8", shortHost)!!, host, shortHost)
        )
    }

    @Test fun `timestamp parser accepts seconds and hms notation within 24h`() {
        assertEquals(42L, ShortLinks.parseTimestampSeconds("42"))
        assertEquals(90L, ShortLinks.parseTimestampSeconds("1m30s"))
        assertEquals(3723L, ShortLinks.parseTimestampSeconds("1h2m3s"))
        assertNull(ShortLinks.parseTimestampSeconds("0"))
        assertNull(ShortLinks.parseTimestampSeconds("90000"))
        assertNull(ShortLinks.parseTimestampSeconds("abc"))
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
        assertNull(AppLinkParser.parse("https://videos.alummahnow.net/en/video/v1", host, shortHost))
        assertNull(AppLinkParser.parse("http://$host/en/video/v1", host, shortHost))
        assertNull(AppLinkParser.parse("https://evil.example/$host/en/video/v1", host, shortHost))
        assertNull(parse("/en/video"))
        assertNull(parse("/en/library"))
        assertNull(parse("/"))
        assertNull(AppLinkParser.parse("not a url ::", host, shortHost))
    }

    @Test fun `host match ignores case`() {
        assertEquals(AppLink.Video("v1", "en"), AppLinkParser.parse("https://VIDEOS.QA.alummahnow.net/en/video/v1", host, shortHost))
    }

    @Test fun `custom scheme watch links still work`() {
        assertEquals(AppLink.Video("v1"), AppLinkParser.parse("alummahnow://watch/v1", host, shortHost))
        assertNull(AppLinkParser.parse("alummahnow://other/v1", host, shortHost))
    }

    @Test fun `ids that could forge an in-app route are refused`() {
        assertNull(parse("/en/video/a%2Fb"))
        assertNull(parse("/en/video/a%3Fb=1"))
        assertNull(parse("/shorts/%25"))
        assertNull(AppLinkParser.parse("https://$shortHost/v/a%2Fb", host, shortHost))
        assertNull(ShortLinks.video("bad/id"))
        assertNull(AppLinkParser.parse("alummahnow://watch/a%2F..", host, shortHost))
        assertNull(parse("/en/@a%2Fb"))
        assertEquals(AppLink.Studio(lang = "en"), parse("/en/studio/a%2Fb/content"))
        assertEquals(AppLink.Studio("ch1", StudioSection.CONTENT, lang = "en"), parse("/en/studio/ch1/content/videos/x%3Fy"))
    }
}
