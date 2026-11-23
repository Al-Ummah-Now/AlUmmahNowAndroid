package net.alummahnow.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.alummahnow.core.bridge.model.LocalizedText

/** Sleep timer presets for Listen mode (Epic 14). */
sealed interface SleepTimerPreset {
    data object Off : SleepTimerPreset
    data class Minutes(val minutes: Int) : SleepTimerPreset {
        val durationMs: Long get() = minutes * 60_000L
    }

    data object EndOfEpisode : SleepTimerPreset

    companion object {
        val STANDARD: List<SleepTimerPreset> = listOf(
            Off,
            Minutes(15),
            Minutes(30),
            Minutes(45),
            Minutes(60),
            EndOfEpisode
        )
    }
}

data class SleepTimerState(
    val preset: SleepTimerPreset = SleepTimerPreset.Off,
    val expiresAtMs: Long? = null
) {
    val isActive: Boolean get() = preset !is SleepTimerPreset.Off

    fun remainingMs(nowMs: Long): Long? = when (preset) {
        SleepTimerPreset.Off -> null
        SleepTimerPreset.EndOfEpisode -> null
        is SleepTimerPreset.Minutes -> expiresAtMs?.let { (it - nowMs).coerceAtLeast(0L) }
    }

    fun isExpired(nowMs: Long): Boolean = when (preset) {
        is SleepTimerPreset.Minutes -> expiresAtMs != null && nowMs >= expiresAtMs
        else -> false
    }
}

data class QueueEntry(
    val videoId: String,
    val title: LocalizedText,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val playUrl: String? = null,
    val audioUrl: String? = null
)

data class ListenModeState(
    val listenModeEnabled: Boolean = false,
    val isAudioOnly: Boolean = false,
    val sleepTimer: SleepTimerState = SleepTimerState(),
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1
) {
    val currentEntry: QueueEntry? get() = queue.getOrNull(currentIndex)
    val upNext: List<QueueEntry>
        get() = if (currentIndex in queue.indices) queue.drop(currentIndex + 1) else queue
}

/**
 * Pure Listen mode, sleep timer, and up-next queue controller (E14-S8).
 * Switching to audio-only disables the video track on the active [Player] without
 * losing playback position or playback speed.
 */
class ListenModeController(
    listenModeEnabled: Boolean = false,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val mutableState = MutableStateFlow(ListenModeState(listenModeEnabled = listenModeEnabled))
    val state: StateFlow<ListenModeState> = mutableState.asStateFlow()

    fun setFeatureEnabled(enabled: Boolean) {
        mutableState.update { cur ->
            if (!enabled) {
                cur.copy(listenModeEnabled = false, isAudioOnly = false, sleepTimer = SleepTimerState())
            } else {
                cur.copy(listenModeEnabled = true)
            }
        }
    }

    fun setAudioOnly(audioOnly: Boolean, player: Player? = null) {
        if (audioOnly && !mutableState.value.listenModeEnabled) return
        mutableState.update { it.copy(isAudioOnly = audioOnly) }
        val entry = mutableState.value.currentEntry
        if (player != null) {
            val targetUrl = if (audioOnly) entry?.audioUrl else entry?.playUrl
            if (!targetUrl.isNullOrBlank()) {
                swapMediaUrlPreservingPlayback(player, entry!!.videoId, targetUrl)
            }
            applyAudioOnlyTrackSelection(player, audioOnly)
        }
    }

    fun setSleepTimer(preset: SleepTimerPreset) {
        val expiresAt = when (preset) {
            is SleepTimerPreset.Minutes -> clockMs() + preset.durationMs
            else -> null
        }
        mutableState.update { it.copy(sleepTimer = SleepTimerState(preset = preset, expiresAtMs = expiresAt)) }
    }

    fun cancelSleepTimer() = setSleepTimer(SleepTimerPreset.Off)

    /**
     * Checks whether a timed sleep timer has expired; if so, clears it and pauses [player].
     * Returns true when playback was paused by the timer.
     */
    fun tickSleepTimer(player: Player? = null): Boolean {
        val timer = mutableState.value.sleepTimer
        if (!timer.isExpired(clockMs())) return false
        mutableState.update { it.copy(sleepTimer = SleepTimerState()) }
        player?.pause()
        return true
    }

    /** Called when the current episode finishes; pauses when EndOfEpisode is armed. */
    fun onEpisodeEnded(player: Player? = null): Boolean {
        if (mutableState.value.sleepTimer.preset != SleepTimerPreset.EndOfEpisode) return false
        mutableState.update { it.copy(sleepTimer = SleepTimerState()) }
        player?.pause()
        return true
    }

    fun setNowPlaying(entry: QueueEntry) {
        mutableState.update { cur ->
            val existing = cur.queue.indexOfFirst { it.videoId == entry.videoId }
            if (existing >= 0) {
                cur.copy(currentIndex = existing)
            } else {
                val updated = listOf(entry) + cur.queue
                cur.copy(queue = updated, currentIndex = 0)
            }
        }
    }

    fun playNext(entry: QueueEntry) {
        mutableState.update { cur ->
            val filtered = cur.queue.filterNot { it.videoId == entry.videoId }.toMutableList()
            val insertAt = if (cur.currentIndex >= 0) {
                (cur.currentIndex + 1).coerceAtMost(filtered.size)
            } else {
                0
            }
            filtered.add(insertAt, entry)
            cur.copy(queue = filtered)
        }
    }

    fun addToEnd(entry: QueueEntry) {
        mutableState.update { cur ->
            if (cur.queue.any { it.videoId == entry.videoId }) {
                cur
            } else {
                cur.copy(queue = cur.queue + entry)
            }
        }
    }

    fun removeFromQueue(videoId: String) {
        mutableState.update { cur ->
            val idx = cur.queue.indexOfFirst { it.videoId == videoId }
            if (idx < 0) return@update cur
            val updated = cur.queue.toMutableList().apply { removeAt(idx) }
            val nextIdx = when {
                updated.isEmpty() -> -1
                idx < cur.currentIndex -> cur.currentIndex - 1
                idx == cur.currentIndex -> cur.currentIndex.coerceAtMost(updated.lastIndex)
                else -> cur.currentIndex
            }
            cur.copy(queue = updated, currentIndex = nextIdx)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        mutableState.update { cur ->
            if (fromIndex !in cur.queue.indices || toIndex !in cur.queue.indices || fromIndex == toIndex) {
                return@update cur
            }
            val currentId = cur.currentEntry?.videoId
            val updated = cur.queue.toMutableList()
            val item = updated.removeAt(fromIndex)
            updated.add(toIndex, item)
            val newIndex = currentId?.let { id -> updated.indexOfFirst { it.videoId == id } } ?: cur.currentIndex
            cur.copy(queue = updated, currentIndex = newIndex)
        }
    }

    fun advance(): QueueEntry? {
        var next: QueueEntry? = null
        mutableState.update { cur ->
            val nextIdx = cur.currentIndex + 1
            if (nextIdx in cur.queue.indices) {
                next = cur.queue[nextIdx]
                cur.copy(currentIndex = nextIdx)
            } else {
                cur
            }
        }
        return next
    }

    fun clearUpNext() {
        mutableState.update { cur ->
            val current = cur.currentEntry
            if (current != null) {
                cur.copy(queue = listOf(current), currentIndex = 0)
            } else {
                cur.copy(queue = emptyList(), currentIndex = -1)
            }
        }
    }

    companion object {
        /** Swaps to the existing HLS audio rendition (`a/orig/index.m3u8` / `master.m3u8`) preserving position, speed and play state. */
        fun swapMediaUrlPreservingPlayback(player: Player, videoId: String, url: String) {
            val pos = player.currentPosition
            val speed = player.playbackParameters
            val wasPlaying = player.playWhenReady
            val mime = if (url.contains(".m4a", ignoreCase = true)) MimeTypes.AUDIO_MP4 else MimeTypes.APPLICATION_M3U8
            val item = MediaItem.Builder().setMediaId(videoId).setUri(url).setMimeType(mime).build()
            player.setMediaItem(item, pos)
            player.playbackParameters = speed
            player.prepare()
            player.playWhenReady = wasPlaying
        }

        /** Fallback when no standalone audio rendition exists yet: disables or restores the video track. */
        fun applyAudioOnlyTrackSelection(player: Player, audioOnly: Boolean) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
                .build()
        }
    }
}
