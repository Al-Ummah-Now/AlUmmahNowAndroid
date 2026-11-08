package net.alummahnow.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.alummahnow.core.bridge.AunLog

/**
 * Owns the one player for the app: background audio, lock-screen / notification controls (spec 13.5),
 * Chromecast (13.6) via [CastPlayer] wrapping the local ExoPlayer, and the media browse tree used by
 * Android Auto (14.4).
 */
class AunPlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val deps = application as PlayerDependencies
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SeekGestures.SKIP_MS)
            .setSeekForwardIncrementMs(SeekGestures.SKIP_MS)
            .build()
        val player: Player = if (deps.castEnabled) {
            runCatching { CastPlayer.Builder(this).setLocalPlayer(exo).build() }
                .onFailure { AunLog.w("player", "cast unavailable, local playback only", "error" to it.message) }
                .getOrDefault(exo)
        } else {
            exo
        }
        player.addListener(PlaybackLogger)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, deps.sessionActivityClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(this, deps.catalog, scope))
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background only while something is actually playing (platform guidance).
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private object PlaybackLogger : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AunLog.e("player", "playback error", error, "code" to error.errorCodeName)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            AunLog.i("player", "item", "id" to mediaItem?.mediaId, "reason" to reason)
        }
    }
}
