package net.alummahnow.viewer.instant

import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantWatchTest {
    private val webHost = "videos.qa.alummahnow.net"
    private val shortHost = "qa.alummahnow.link"

    @Test fun `instant budget stays at 15 MB`() {
        assertEquals(15_000_000L, InstantInvocation.PLAY_INSTANT_MAX_BYTES)
    }

    @Test fun `resolves shared video and short links with optional timestamp`() {
        val fromShort = InstantInvocation.resolve("https://$shortHost/v/vid_42?t=90&lang=ar", webHost, shortHost)
        assertEquals(InstantInvocation(videoId = "vid_42", startSeconds = 90L, lang = "ar"), fromShort)

        val fromWeb = InstantInvocation.resolve("https://$webHost/en/shorts/sh_7", webHost, shortHost)
        assertEquals(InstantInvocation(videoId = "sh_7", startSeconds = null, lang = "en"), fromWeb)

        assertNull(InstantInvocation.resolve("https://$shortHost/c/ch_1", webHost, shortHost))
        assertNull(InstantInvocation.resolve("https://evil.example/v/vid_42", webHost, shortHost))
    }

    @Test fun `kids category hides store install prompt`() {
        fun detail(category: String?) = VideoDetail(
            summary = VideoSummary(
                id = "v1",
                title = LocalizedText.of("Title"),
                thumbnailUrl = null,
                durationSeconds = 60L,
                views = 10L,
                channelId = "ch1",
                channelName = "Channel",
                publishedAt = null,
                category = category
            ),
            description = LocalizedText.of(""),
            playUrl = "https://cdn.example/master.m3u8",
            availableResolutions = emptyList(),
            chapters = emptyList(),
            subtitles = emptyList(),
            likes = 0L,
            tags = emptyList(),
            isLive = false,
            isReady = true
        )
        assertTrue(InstantInvocation.isKidsSafeOnly(detail("islamic_kids")))
        assertFalse(InstantInvocation.isKidsSafeOnly(detail("quran_tafsir")))
    }
}
