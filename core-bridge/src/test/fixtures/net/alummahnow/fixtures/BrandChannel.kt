package net.alummahnow.fixtures

import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.core.bridge.studio.CommentsSummary
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.DailyMetric
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.VideoStat

/**
 * The brand channel every screenshot fixture shows: the one source of its videos, views, dates and analytics, compiled
 * into the test sources of each module that draws them (viewer Home and Watch, Studio features, the Studio app), so a
 * video never has two view counts in two frames.
 *
 * - Every published video went up in September 2026, before the October analytics window ([OCTOBER]).
 * - The scheduled video goes out in December, after the window.
 * - Lifetime [ChannelVideo.views] are at least the video's October views.
 */
internal object BrandChannel {
    const val NAME_EN = "Al Ummah Now"
    const val NAME_AR = "الأمة الآن"
    const val HANDLE = "alummahnow"

    /** The channel named in a frame's language. */
    fun name(arabic: Boolean) = if (arabic) NAME_AR else NAME_EN

    /** One video: [views] are lifetime views, [octoberViews] its views in the 1–28 October window. */
    data class ChannelVideo(
        val id: String,
        val en: String,
        val ar: String,
        val thumb: String,
        val views: Long,
        val octoberViews: Long,
        val seconds: Long,
        val category: String,
        val createdAt: String
    ) {
        val thumbnailUrl: String get() = "$THUMBS$thumb.jpg"
    }

    private fun sep(day: Int) = "2026-09-%02dT10:00:00Z".format(day)

    val kahf = ChannelVideo("v4", "Surat Al-Kahf recitation", "تلاوة سورة الكهف", "kahf", 8_020, 7_930, 2_710, "quran_recitation", sep(7))
    val tafsir = ChannelVideo("v1", "Introduction to Tafsir", "مقدمة في التفسير", "tafsir", 1_870, 1_504, 1_830, "quran_tafsir", sep(6))
    val khutbah = ChannelVideo("v5", "Friday khutbah: patience", "خطبة الجمعة: الصبر", "khutbah", 1_690, 1_300, 1_260, "khutbah", sep(5))
    val fiqh = ChannelVideo("v3", "Fiqh of Prayer", "فقه الصلاة", "fiqh", 2_460, 2_120, 3_725, "fiqh", sep(4))
    val ramadan = ChannelVideo("v2", "Ramadan reminders", "تذكيرات رمضان", "ramadan", 12_000, 11_820, 45, "ramadan", sep(2))

    // The channel's first upload: its 640 views all came in September.
    val patience = ChannelVideo("v8", "Patience in hardship", "الصبر عند الشدائد", "patience", 640, 0, 980, "islamic_lifestyle", sep(1))

    /** Published, newest first. */
    val published = listOf(kahf, tafsir, khutbah, fiqh, ramadan, patience)

    val draft = ChannelVideo("v6", "Seerah overview", "نظرة عامة على السيرة", "seerah", 0, 0, 1_265, "islamic_history", sep(3))

    val scheduled = ChannelVideo(
        "v7",
        "Tafsir of Surah Al-Kahf, part 2",
        "تفسير سورة الكهف، الجزء الثاني",
        "kahf2",
        0,
        0,
        2_340,
        "quran_tafsir",
        "2026-12-09T10:00:00Z"
    )

    /** Published, scheduled, drafts, processing, failed: the lists above, so the tiles and the cards agree. */
    val counts = ContentCounts(published.size, 1, 1, 0, 0)

    /** Comments held for review, and in the inbox. */
    val comments = CommentsSummary(held = 2, inbox = 5)

    // --- Model objects ---

    /** The video as Home and Watch get it (titles in both languages, the channel named in the frame's language). */
    fun summary(v: ChannelVideo, arabic: Boolean) = VideoSummary(
        id = v.id,
        title = LocalizedText(v.en, mapOf("ar" to v.ar)),
        thumbnailUrl = v.thumbnailUrl,
        durationSeconds = v.seconds,
        views = v.views,
        channelId = CHANNEL_ID,
        channelName = name(arabic),
        publishedAt = null,
        category = v.category
    )

    /** The video as Studio lists it. */
    fun studio(v: ChannelVideo, status: ContentStatus = ContentStatus.PUBLISHED) = StudioVideo(
        v.id,
        v.en,
        thumbnailUrl = v.thumbnailUrl,
        createdAt = v.createdAt,
        titleLocalized = mapOf("ar" to v.ar),
        views = v.views,
        durationSeconds = v.seconds.toDouble(),
        category = v.category,
        status = status,
        isDraft = status == ContentStatus.DRAFTS
    )

    val studioPublished: List<StudioVideo> get() = published.map { studio(it) }
    val studioDraft: StudioVideo get() = studio(draft, ContentStatus.DRAFTS)
    val studioScheduled: StudioVideo get() = studio(scheduled, ContentStatus.SCHEDULED)

    /**
     * October 2026, the 28 days the Analytics frames show: a weekly rhythm (Fridays highest, Mondays lowest), day-to-day
     * noise and two spikes (the 9th and 23rd). Adds up to the published videos' October views (24,674).
     */
    val OCTOBER: List<Long> = listOf(
        755L, 1015L, 908L, 704L, 659L, 674L, 684L, 814L, 1809L, 910L, 732L, 631L, 709L, 819L,
        788L, 1114L, 979L, 891L, 718L, 733L, 872L, 806L, 1880L, 956L, 801L, 684L, 748L, 881L
    )

    private val topStats = listOf(
        // likes, comments, watch time (s), average view (s)
        ramadan to longArrayOf(800, 90, 360_000, 40),
        kahf to longArrayOf(610, 41, 540_000, 1_355),
        fiqh to longArrayOf(160, 12, 410_000, 1_420),
        tafsir to longArrayOf(150, 40, 300_000, 640),
        khutbah to longArrayOf(140, 18, 280_000, 760)
    )

    /** The channel's October analytics: totals from the published videos, top videos by October views. */
    val analytics: ChannelAnalytics
        get() {
            val total = published.sumOf { it.octoberViews }
            check(total == OCTOBER.sum()) { "October series ${OCTOBER.sum()} != October views $total" }
            return ChannelAnalytics(
                totalViews = total,
                totalVideos = published.size.toLong(),
                totalSubscribers = 412,
                totalLikes = 1_980,
                totalComments = 133,
                avgViewsPerVideo = total.toDouble() / published.size,
                engagementRate = 8.2,
                topVideos = topStats.map { (v, s) ->
                    VideoStat(v.id, v.en, null, v.octoberViews, s[0], s[1], s[2], s[3].toDouble(), mapOf("ar" to v.ar))
                },
                daily = OCTOBER.mapIndexed { i, views ->
                    DailyMetric("2026-10-%02d".format(i + 1), views, views * 7 / 100, views / 90, views / 120, views * 240)
                }
            )
        }

    const val CHANNEL_ID = "c1"
    private const val THUMBS = "fixture:thumbs/"
}
