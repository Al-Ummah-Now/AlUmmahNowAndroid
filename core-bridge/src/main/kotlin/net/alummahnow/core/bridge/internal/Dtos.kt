package net.alummahnow.core.bridge.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class ListEnvelope(val data: List<VideoDto> = emptyList(), val cursor: String? = null)

@Serializable
internal data class VideoDto(
    val id: String,
    val title: String = "",
    val titleLocalized: Map<String, String> = emptyMap(),
    val description: String = "",
    val descriptionLocalized: Map<String, String> = emptyMap(),
    val thumbnailUrl: String? = null,
    val playUrl: String? = null,
    val duration: Double? = null,
    val views: Long = 0,
    val channelId: String? = null,
    val channelName: String? = null,
    val dateCreated: String? = null,
    val category: String? = null,
    val availableResolutions: List<String> = emptyList(),
    val chapters: ChaptersDto? = null,
    val subtitles: List<SubtitleDto> = emptyList(),
    val likesCount: Long = 0,
    val tags: List<String> = emptyList(),
    val isLive: Boolean = false,
    val isPublic: Boolean = true,
    val uploadStatus: String? = null
)

@Serializable
internal data class ChaptersDto(val chapters: List<ChapterDto> = emptyList())

@Serializable
internal data class ChapterDto(
    val id: String,
    val startTime: Double,
    val endTime: Double? = null,
    val title: String = "",
    val titleLocalized: Map<String, String> = emptyMap()
)

@Serializable
internal data class SubtitleDto(
    val language: String,
    val label: String = "",
    val url: String,
    val status: String? = null,
    val isDefault: Boolean = false,
    val kind: String? = null,
    val extra: JsonElement? = null
)
