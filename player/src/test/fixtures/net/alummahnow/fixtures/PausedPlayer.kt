package net.alummahnow.fixtures

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A player that is ready and paused at [positionMs] of a [durationMs] video: what a screen looks like once the stream
 * has loaded and before (or after) someone presses play. Screenshot tests hand it to the real player UI, so the frames
 * show its settled controls (play button, seek bar, times) instead of a connecting loader. It never loads or plays
 * anything: setting a new item or preparing keeps the same paused state.
 */
@OptIn(UnstableApi::class)
internal class PausedPlayer(mediaId: String, durationMs: Long, private val positionMs: Long = 0) :
    SimpleBasePlayer(Looper.getMainLooper()) {
    var lastSeekMs: Long? = null
        private set

    private val item = MediaItemData.Builder(mediaId)
        .setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
        .setDurationUs(durationMs * US_PER_MS)
        .setIsSeekable(true)
        .build()

    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_PREPARE,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_TIMELINE,
                    COMMAND_GET_METADATA,
                    COMMAND_SET_MEDIA_ITEM,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_BACK,
                    COMMAND_SEEK_FORWARD,
                    COMMAND_GET_TRACKS,
                    COMMAND_RELEASE
                )
                .build()
        )
        .setPlaylist(listOf(item))
        .setCurrentMediaItemIndex(0)
        .setPlaybackState(STATE_READY)
        .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setContentPositionMs(positionMs)
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = done()

    override fun handlePrepare(): ListenableFuture<*> = done()

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> =
        done()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        lastSeekMs = positionMs
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> = done()

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val US_PER_MS = 1_000L
    }
}
