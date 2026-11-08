package net.alummahnow.core.bridge.model

import java.util.Locale

/**
 * Text that the API provides per language (e.g. `titleLocalized: {en, ar}`), with a fallback when a
 * translation is missing. Mirrors how the web app picks localised fields.
 */
data class LocalizedText(val fallback: String, val translations: Map<String, String> = emptyMap()) {
    fun resolve(locale: Locale = Locale.getDefault()): String =
        translations[locale.language]?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        fun of(text: String) = LocalizedText(text)
    }
}

data class VideoSummary(
    val id: String,
    val title: LocalizedText,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val views: Long,
    val channelId: String?,
    val channelName: String?,
    val publishedAt: String?,
    val category: String?
)

data class Chapter(val id: String, val startSeconds: Double, val endSeconds: Double?, val title: LocalizedText)

data class SubtitleTrack(val language: String, val label: String, val url: String, val isDefault: Boolean)

data class VideoDetail(
    val summary: VideoSummary,
    val description: LocalizedText,
    val playUrl: String?,
    val availableResolutions: List<String>,
    val chapters: List<Chapter>,
    val subtitles: List<SubtitleTrack>,
    val likes: Long,
    val tags: List<String>,
    val isLive: Boolean,
    val isReady: Boolean
) {
    val id: String get() = summary.id
}

/** A home rail. [kind] drives the localised heading in the UI; the core never returns display strings. */
data class Rail(val kind: RailKind, val videos: List<VideoSummary>)

enum class RailKind { CONTINUE_WATCHING, TRENDING, RECOMMENDED, POPULAR, RECENT, CATEGORY }
