package net.alummahnow.studio

import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.StudioVideo

/**
 * The one channel library every Studio frame shows (store, framed and feature-state frames): the same five published
 * videos as the viewer fixtures (titles, views, durations, art), plus one draft and one scheduled video. Counts agree.
 */
internal object StudioLibrary {
    private fun video(
        id: String,
        en: String,
        ar: String,
        thumb: String,
        day: Int,
        views: Long,
        seconds: Double,
        category: String,
        status: ContentStatus = ContentStatus.PUBLISHED
    ) = StudioVideo(
        id,
        en,
        thumbnailUrl = "${FIXTURE_THUMBS}$thumb.jpg",
        createdAt = "2026-09-%02dT10:00:00Z".format(day),
        titleLocalized = mapOf("ar" to ar),
        views = views,
        durationSeconds = seconds,
        category = category,
        status = status
    )

    /** Newest first. */
    val published = listOf(
        video("v4", "Surat Al-Kahf recitation", "تلاوة سورة الكهف", "kahf", 7, 8_020, 2_710.0, "quran_recitation"),
        video("v1", "Introduction to Tafsir", "مقدمة في التفسير", "tafsir", 6, 1_520, 1_830.0, "quran_tafsir"),
        video("v5", "Friday khutbah: patience", "خطبة الجمعة: الصبر", "khutbah", 5, 1_310, 1_260.0, "khutbah"),
        video("v3", "Fiqh of Prayer", "فقه الصلاة", "fiqh", 4, 2_140, 3_725.0, "fiqh"),
        video("v2", "Ramadan reminders", "تذكيرات رمضان", "ramadan", 2, 12_000, 45.0, "ramadan")
    )

    val draft = video("v6", "Seerah overview", "نظرة عامة على السيرة", "seerah", 3, 0, 1_265.0, "islamic_history", ContentStatus.DRAFTS)

    val scheduled = video(
        "v7",
        "Tafsir of Surah Al-Kahf, part 2",
        "تفسير سورة الكهف، الجزء الثاني",
        "kahf2",
        9,
        0,
        2_340.0,
        "quran_tafsir",
        ContentStatus.SCHEDULED
    )

    /** Published, scheduled, drafts, processing, failed. */
    val counts = ContentCounts(published.size, 1, 1, 0, 0)
}
