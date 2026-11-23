package net.alummahnow.player

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import java.util.Locale
import net.alummahnow.core.bridge.model.LiveStream
import net.alummahnow.core.bridge.model.LiveStreamStatus
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary

/** Builds Media3 items from core models. One place, so phone, TV and Android Auto behave identically. */
object MediaItems {
    const val EXTRA_CHANNEL_ID = "aun.channelId"
    const val EXTRA_LIVE_DVR_WINDOW_SECONDS = "aun.liveDvrWindowSeconds"
    private const val LIVE_TARGET_OFFSET_MS = 3_000L
    private const val LIVE_MIN_SPEED = 0.97f
    private const val LIVE_MAX_SPEED = 1.03f

    fun playable(video: VideoDetail, locale: Locale = Locale.getDefault()): MediaItem {
        val playUrl = requireNotNull(video.playUrl) { "video ${video.id} has no play URL" }
        val subtitles = video.subtitles.map { track ->
            MediaItem.SubtitleConfiguration.Builder(track.url.toUri())
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(track.language)
                .setLabel(track.label)
                .setSelectionFlags(if (track.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setMediaId(video.id)
            .setUri(playUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(metadata(video.summary, locale, playable = true))
            .build()
    }

    /**
     * Builds a Media3 item for a live stream, video premiere, or finished live recording (HLS).
     * Active live streams use a 3 s target latency offset with gentle speed catch-up.
     */
    fun playableLive(
        stream: LiveStream,
        playbackUrl: String? = null,
        locale: Locale = Locale.getDefault()
    ): MediaItem {
        val resolvedUrl = playbackUrl
            ?: (if (stream.status == LiveStreamStatus.ENDED) stream.vodUrl ?: stream.playbackUrl else stream.playbackUrl)
        val uri = requireNotNull(resolvedUrl) { "live stream ${stream.id} has no playback URL" }
        val builder = MediaItem.Builder()
            .setMediaId(stream.id)
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(stream.title.resolve(locale))
                    .setArtist(stream.channelName)
                    .setArtworkUri(stream.thumbnailUrl?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                    .setExtras(
                        Bundle().apply {
                            putString(EXTRA_CHANNEL_ID, stream.channelId)
                            putLong(EXTRA_LIVE_DVR_WINDOW_SECONDS, if (stream.dvrEnabled) stream.dvrWindowSeconds else 0L)
                        }
                    )
                    .build()
            )
        if (stream.status == LiveStreamStatus.LIVE) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinPlaybackSpeed(LIVE_MIN_SPEED)
                    .setMaxPlaybackSpeed(LIVE_MAX_SPEED)
                    .build()
            )
        }
        return builder.build()
    }

    /** A browsable/playable entry without a URI; the service resolves it on play (Android Auto). */
    fun browsableVideo(video: VideoSummary, locale: Locale = Locale.getDefault()): MediaItem =
        MediaItem.Builder().setMediaId(video.id).setMediaMetadata(metadata(video, locale, playable = true)).build()

    fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun metadata(video: VideoSummary, locale: Locale, playable: Boolean): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(video.title.resolve(locale))
            .setArtist(video.channelName)
            .setArtworkUri(video.thumbnailUrl?.let(Uri::parse))
            .setDurationMs(video.durationSeconds?.times(1000))
            .setIsBrowsable(false)
            .setIsPlayable(playable)
            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
            .setExtras(Bundle().apply { putString(EXTRA_CHANNEL_ID, video.channelId) })
            .build()
}
