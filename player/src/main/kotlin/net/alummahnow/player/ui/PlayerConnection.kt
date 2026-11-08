package net.alummahnow.player.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.player.AunPlaybackService

/** Connects to [AunPlaybackService]; the value is null until connected and after disposal. */
@Composable
fun rememberMediaController(): State<MediaController?> {
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, AunPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { state.value = it }
                    .onFailure { AunLog.e("player", "controller connect failed", it) }
            },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            state.value = null
            MediaController.releaseFuture(future)
        }
    }
    return state
}
