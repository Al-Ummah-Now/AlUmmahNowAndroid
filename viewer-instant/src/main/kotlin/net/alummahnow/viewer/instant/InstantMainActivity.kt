package net.alummahnow.viewer.instant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.media3.exoplayer.ExoPlayer
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.core.bridge.links.AppLink
import net.alummahnow.core.bridge.links.AppLinkParser
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.player.MediaItems
import net.alummahnow.player.ui.AunVideoPlayer

/** Resolved target for the Google Play Instant experience (`E22-D8`). */
data class InstantInvocation(
    val videoId: String,
    val startSeconds: Long? = null,
    val lang: String? = null
) {
    companion object {
        /** Google Play Instant maximum downloadable size budget (15 MB). */
        const val PLAY_INSTANT_MAX_BYTES: Long = 15L * 1_000_000L

        fun resolve(url: String?, webHost: String, shortLinkHost: String): InstantInvocation? {
            if (url.isNullOrBlank()) return null
            val parsed = AppLinkParser.parseInvocation(url, webHost, shortLinkHost) ?: return null
            val videoId = when (val link = parsed.link) {
                is AppLink.Video -> link.id
                is AppLink.Short -> link.id
                else -> return null
            }
            return InstantInvocation(videoId = videoId, startSeconds = parsed.startSeconds, lang = parsed.lang)
        }

        /** Kids-category videos hide external store/install prompts (`Epic 20` / `E22 §9.3`). */
        fun isKidsSafeOnly(video: VideoDetail): Boolean = video.summary.category == "islamic_kids"
    }
}

class InstantMainActivity : ComponentActivity() {
    private val invocationState = mutableStateOf<InstantInvocation?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val catalog = TemporaryCatalogRepository(BuildConfig.API_BASE_URL)
        invocationState.value = InstantInvocation.resolve(intent?.data?.toString(), BuildConfig.WEB_HOST, BuildConfig.SHORT_LINK_HOST)
        setContent {
            AunTheme {
                InstantWatchScreen(
                    catalog = catalog,
                    invocation = invocationState.value,
                    onInstallFullApp = ::promptInstallFullApp
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        invocationState.value = InstantInvocation.resolve(intent.data?.toString(), BuildConfig.WEB_HOST, BuildConfig.SHORT_LINK_HOST)
    }

    private fun promptInstallFullApp() {
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, marketUri)) }
            .recoverCatching { startActivity(Intent(Intent.ACTION_VIEW, webUri)) }
    }
}

@Composable
private fun InstantWatchScreen(
    catalog: CatalogRepository,
    invocation: InstantInvocation?,
    onInstallFullApp: () -> Unit
) {
    val context = LocalContext.current
    val locale = currentLocale()
    var video by remember(invocation) { mutableStateOf<VideoDetail?>(null) }
    var failed by remember(invocation) { mutableStateOf(false) }

    LaunchedEffect(invocation) {
        val target = invocation ?: return@LaunchedEffect
        failed = false
        runCatching { catalog.video(target.videoId) }
            .onSuccess { video = it }
            .onFailure { failed = true }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            invocation == null || failed -> ErrorState(
                message = stringResource(DesignR.string.aun_error_not_found),
                onRetry = onInstallFullApp
            )
            video == null -> FullScreenLoading()
            else -> {
                val loaded = video!!
                val player = remember(loaded.id) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItems.playable(loaded, locale))
                        prepare()
                        invocation.startSeconds?.let { seekTo(it * MS_PER_SECOND) }
                        playWhenReady = true
                    }
                }
                DisposableEffect(player) { onDispose { player.release() } }
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
                    verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg)
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                        AunVideoPlayer(
                            player = player,
                            isFullscreen = false,
                            onToggleFullscreen = {},
                            controlsInitiallyVisible = true,
                            modifier = Modifier.fillMaxSize(),
                            poster = loaded.summary.thumbnailUrl
                        )
                    }
                    Column(
                        Modifier.padding(horizontal = FanousTokens.Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
                    ) {
                        Text(
                            loaded.summary.title.resolve(locale),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() }
                        )
                        loaded.summary.channelName?.takeIf { it.isNotBlank() }?.let { channelName ->
                            Text(
                                channelName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!InstantInvocation.isKidsSafeOnly(loaded)) {
                            Button(
                                onClick = onInstallFullApp,
                                modifier = Modifier.fillMaxWidth().padding(top = FanousTokens.Spacing.sm)
                            ) {
                                Text(stringResource(DesignR.string.aun_instant_install_app))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MS_PER_SECOND = 1_000L
