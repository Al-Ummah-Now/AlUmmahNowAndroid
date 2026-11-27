package net.alummahnow.viewer

import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.links.AppLink
import net.alummahnow.core.bridge.links.AppLinkParser
import net.alummahnow.core.bridge.links.ShortLinks
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.safety.ViewerReportUrls
import net.alummahnow.core.bridge.telemetry.TelemetryConsent
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.feature.home.HomeScreen
import net.alummahnow.feature.home.HomeUiState
import net.alummahnow.feature.home.HomeViewModel
import net.alummahnow.feature.live.LiveListScreen
import net.alummahnow.feature.live.LiveListUiState
import net.alummahnow.feature.live.LiveListViewModel
import net.alummahnow.feature.live.LiveRail
import net.alummahnow.feature.live.LiveSafetyActions
import net.alummahnow.feature.live.LiveWatchScreen
import net.alummahnow.feature.live.LiveWatchUiState
import net.alummahnow.feature.live.LiveWatchViewModel
import net.alummahnow.feature.live.RunLive
import net.alummahnow.feature.watch.WatchSafetyActions
import net.alummahnow.feature.watch.WatchScreen
import net.alummahnow.feature.watch.WatchUiState
import net.alummahnow.feature.watch.WatchViewModel
import net.alummahnow.player.ui.rememberMediaController

class MainActivity : ComponentActivity() {
    /** True while a video is playing on the watch page, so leaving the app enters picture-in-picture. */
    private var pipEligible = false
    private val inPip = mutableStateOf(false)

    /** Routes opened from a link (App Links or `alummahnow://watch/{id}`), delivered once each. */
    private val links = Channel<String>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pipListener = Consumer<PictureInPictureModeChangedInfo> { inPip.value = it.isInPictureInPictureMode }
        addOnPictureInPictureModeChangedListener(pipListener)
        val container = (application as AunViewerApp).container
        if (savedInstanceState == null) handle(intent)
        setContent {
            val theme by container.displayPrefs.theme.collectAsState(initial = null)
            val deviceDark = isSystemInDarkTheme()
            LaunchedEffect(Unit) { container.displayPrefs.adoptDeviceMode(deviceDark) }
            AunTheme(mode = theme) {
                ViewerNavHost(
                    container,
                    inPip.value,
                    links.receiveAsFlow(),
                    onPipEligible = ::setPipEligible,
                    onFullscreen = ::setFullscreen,
                    openInBrowser = ::openInBrowser
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        when (val link = AppLinkParser.parse(url, BuildConfig.WEB_HOST)) {
            is AppLink.Video -> links.trySend(Routes.watch(link.id))
            is AppLink.Short -> links.trySend(Routes.watch(link.id))
            is AppLink.Live -> links.trySend(Routes.live(link.id))
            // No native screen for these yet: show the page on the site instead of a dead end.
            is AppLink.Playlist, is AppLink.Post, is AppLink.Channel -> openInBrowser(url)
            // The viewer has no sign-in; the app simply opens.
            is AppLink.AuthCallback, is AppLink.Studio, null -> Unit
        }
    }

    /**
     * Opens [url] in the browser by package, so the verified App Link does not resolve straight back to this app.
     * Returns false when no browser is installed.
     */
    internal fun openInBrowser(url: String): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://${BuildConfig.WEB_HOST}/"))
        val browser = packageManager.queryIntentActivities(probe, 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != packageName }
            ?: run {
                AunLog.w("links", "no browser for link")
                return false
            }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser))
            true
        } catch (e: ActivityNotFoundException) {
            AunLog.w("links", "browser refused link", "type" to e.javaClass.simpleName)
            false
        }
    }

    private fun setPipEligible(eligible: Boolean) {
        pipEligible = eligible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setAutoEnterEnabled(eligible).build()
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ auto-enters via params; older versions need an explicit call.
        if (pipEligible && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    fun setFullscreen(fullscreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

object Routes {
    const val HOME = "home"
    const val WATCH = "watch/{id}"
    fun watch(id: String) = "watch/$id"
    const val LIVE_LIST = "live"
    const val LIVE = "live/{id}"
    fun live(id: String) = "live/$id"
}

@Composable
internal fun ViewerNavHost(
    container: ViewerContainer,
    inPip: Boolean,
    links: Flow<String>,
    onPipEligible: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    openInBrowser: (String) -> Boolean,
    rememberPlayer: @Composable () -> Player? = { rememberMediaController().value }
) {
    val nav = rememberNavController()
    LaunchedEffect(nav) {
        links.collect { route ->
            // Ids are validated by AppLinkParser; a route that still fails is logged, never a crash.
            runCatching { nav.navigate(route) { launchSingleTop = true } }
                .onFailure { AunLog.w("links", "link not routed", "type" to it.javaClass.simpleName) }
        }
    }
    val context = LocalContext.current
    val locale = currentLocale()
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    var blockedListOpen by remember { mutableStateOf(false) }
    val theme by container.displayPrefs.theme.collectAsState(initial = null)
    val telemetryEnabled by container.displayPrefs.telemetryEnabled.collectAsState()
    val telemetrySaveUncertain by container.displayPrefs.telemetrySaveUncertain.collectAsState()
    val telemetryWarningRes by container.displayPrefs.telemetryWarningRes.collectAsState()
    val lastPurgeFailed by container.telemetry.lastPurgeFailedFlow.collectAsState()
    val telemetryWarning = buildList {
        telemetryWarningRes?.let { add(stringResource(it)) }
        if (lastPurgeFailed && telemetryWarningRes != DesignR.string.aun_diagnostics_purge_warning) {
            add(stringResource(DesignR.string.aun_diagnostics_purge_warning))
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("\n")
    val blockedEntries by container.blockStore.entries.collectAsStateWithLifecycle()
    LaunchedEffect(telemetryEnabled) {
        if (telemetryEnabled) container.telemetry.flush()
    }
    DisposableEffect(Unit) {
        onDispose { container.telemetry.persist() }
    }
    val openPage: (String) -> Unit = { url ->
        if (!openInBrowser(url)) Toast.makeText(context, R.string.settings_no_browser, Toast.LENGTH_SHORT).show()
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container.catalog))
            val state by vm.state.collectAsStateWithLifecycle()
            val liveVm: LiveListViewModel = viewModel(factory = LiveListViewModel.Factory(container.live))
            val live by liveVm.state.collectAsStateWithLifecycle()
            val filteredState = remember(state, blockedEntries) {
                when (val s = state) {
                    is HomeUiState.Content -> s.copy(rails = container.blockStore.filterRails(s.rails))
                    else -> s
                }
            }
            val allLive = (live as? LiveListUiState.Content)?.list?.all.orEmpty()
            val filteredLive = remember(live, blockedEntries) { container.blockStore.filterLiveStreams(allLive) }
            HomeScreen(
                filteredState,
                onVideoClick = { nav.navigate(Routes.watch(it)) },
                onRetry = {
                    vm.load()
                    liveVm.load()
                },
                onOpenSettings = { settingsOpen = true },
                liveRail = { width ->
                    LiveRail(
                        filteredLive,
                        onOpen = { nav.navigate(Routes.live(it)) },
                        onSeeAll = { nav.navigate(Routes.LIVE_LIST) },
                        cardWidth = width
                    )
                },
                hasLive = filteredLive.isNotEmpty(),
                allBlocked = allLive.isNotEmpty() || (state as? HomeUiState.Content)?.rails?.any { it.videos.isNotEmpty() } == true
            )
        }
        composable(
            Routes.WATCH,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: WatchViewModel = viewModel(key = id, factory = WatchViewModel.Factory(container.catalog, id))
            val state by vm.state.collectAsStateWithLifecycle()
            val filteredState = remember(state, blockedEntries) {
                when (val s = state) {
                    is WatchUiState.Content -> s.copy(related = container.blockStore.filterVideos(s.related))
                    else -> s
                }
            }
            val currentVideo = (filteredState as? WatchUiState.Content)?.video
            val isChannelBlocked = remember(currentVideo, blockedEntries) {
                currentVideo != null &&
                    container.blockStore.isChannelBlocked(currentVideo.summary.channelId, currentVideo.summary.channelName)
            }
            val player = rememberPlayer()
            var fullscreen by remember { mutableStateOf(false) }
            PipEligibility(player, onPipEligible)
            LaunchedEffect(fullscreen) { onFullscreen(fullscreen) }
            BackHandler(enabled = fullscreen) { fullscreen = false }
            DisposableEffect(Unit) { onDispose { onFullscreen(false) } }
            val safety = remember(locale.language, player, context) {
                WatchSafetyActions(
                    onReportVideo = {
                        openPage(ViewerReportUrls.videoUrl(it.id, locale.language, BuildConfig.WEB_HOST))
                    },
                    onReportChannel = {
                        val channelId = it.summary.channelId?.takeIf { id -> id.isNotBlank() }
                        if (channelId != null) {
                            openPage(ViewerReportUrls.channelUrl(channelId, locale.language, BuildConfig.WEB_HOST))
                        }
                    },
                    onBlockChannel = {
                        player?.stop()
                        player?.clearMediaItems()
                        container.blockStore.blockChannel(it.summary.channelId, it.summary.channelName)
                        nav.popBackStack()
                    },
                    onCopyLink = { video -> copyVideoLink(context, video, player, locale) }
                )
            }
            WatchScreen(
                state = filteredState,
                player = player,
                isFullscreen = fullscreen,
                isInPictureInPicture = inPip,
                onToggleFullscreen = { fullscreen = !fullscreen },
                onVideoClick = { nav.navigate(Routes.watch(it)) },
                onRetry = vm::load,
                safety = safety,
                isChannelBlocked = isChannelBlocked,
                onUnblockChannel = currentVideo?.summary?.let { s -> { container.blockStore.unblockChannel(s.channelId, s.channelName) } }
            )
        }
        composable(Routes.LIVE_LIST) {
            val vm: LiveListViewModel = viewModel(factory = LiveListViewModel.Factory(container.live))
            val state by vm.state.collectAsStateWithLifecycle()
            val filteredState = remember(state, blockedEntries) {
                when (val s = state) {
                    is LiveListUiState.Content -> s.copy(
                        list = s.list.copy(
                            onAir = container.blockStore.filterLiveStreams(s.list.onAir),
                            coming = container.blockStore.filterLiveStreams(s.list.coming)
                        )
                    )
                    else -> s
                }
            }
            LiveListScreen(filteredState, onOpen = { nav.navigate(Routes.live(it)) }, onEvent = vm::choose, onRetry = vm::load)
        }
        composable(Routes.LIVE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            LiveWatchRoute(
                container = container,
                id = entry.arguments?.getString("id").orEmpty(),
                inPip = inPip,
                onPipEligible = onPipEligible,
                onFullscreen = onFullscreen,
                openPage = openPage,
                rememberPlayer = rememberPlayer,
                onNavigateBack = { nav.popBackStack() }
            )
        }
    }
    if (settingsOpen) {
        DisplaySettingsSheet(
            theme = theme,
            language = AppLanguage.current(context),
            languageSwitchSupported = AppLanguage.isSupported,
            onTheme = { scope.launch { container.displayPrefs.setTheme(it) } },
            onLanguage = { AppLanguage.set(context, it) },
            onDismiss = { settingsOpen = false },
            onOpenUrl = openPage,
            webHost = BuildConfig.WEB_HOST,
            telemetryEnabled = telemetryEnabled || telemetrySaveUncertain,
            telemetryWarning = telemetryWarning,
            onTelemetryChanged = { enabled ->
                if (enabled) {
                    if (container.telemetry.setConsent(TelemetryConsent.FULL)) {
                        if (container.displayPrefs.setTelemetryEnabled(true)) {
                            container.displayPrefs.setTelemetrySaveUncertain(false)
                            container.displayPrefs.setTelemetryWarningRes(null)
                        } else {
                            container.telemetry.setConsent(TelemetryConsent.OFF)
                            val rolledBack = container.displayPrefs.setTelemetryEnabled(false)
                            container.displayPrefs.setTelemetrySaveUncertain(!rolledBack)
                            container.displayPrefs.setTelemetryWarningRes(
                                if (rolledBack) {
                                    DesignR.string.aun_diagnostics_enable_error
                                } else {
                                    DesignR.string.aun_diagnostics_disable_error
                                }
                            )
                        }
                    } else {
                        container.telemetry.setConsent(TelemetryConsent.OFF)
                        container.displayPrefs.setTelemetryWarningRes(DesignR.string.aun_diagnostics_enable_error)
                    }
                } else {
                    val purged = container.telemetry.setConsent(TelemetryConsent.OFF)
                    val saved = container.displayPrefs.setTelemetryEnabled(false)
                    container.displayPrefs.setTelemetrySaveUncertain(!saved)
                    container.displayPrefs.setTelemetryWarningRes(
                        when {
                            !saved -> DesignR.string.aun_diagnostics_disable_error
                            !purged -> DesignR.string.aun_diagnostics_purge_warning
                            else -> null
                        }
                    )
                }
            },
            onOpenBlockedList = {
                settingsOpen = false
                blockedListOpen = true
            }
        )
    }
    if (blockedListOpen) {
        BlockedListDialog(
            entries = blockedEntries,
            onUnblock = container.blockStore::unblock,
            onDismiss = { blockedListOpen = false }
        )
    }
}

private fun copyVideoLink(context: Context, video: VideoDetail, player: Player?, locale: Locale) {
    val seconds = player?.takeIf { it.currentMediaItem?.mediaId == video.id }?.currentPosition
        ?.div(1_000)?.takeIf { it > 0 && !video.isShort }
    val route = if (video.isShort) "shorts" else "video"
    val kind = if (video.isShort) ShortLinks.Kind.SHORT else ShortLinks.Kind.VIDEO
    val language = if (locale.language == "ar") "ar" else "en"
    val page = "${BuildConfig.WEB_BASE_URL}/$language/$route/${Uri.encode(video.id)}"
    val url = ShortLinks.shareUrl(
        ShortLinks.Target(kind, video.id),
        BuildConfig.WEB_BASE_URL,
        if (seconds == null) page else "$page?t=$seconds",
        language,
        video.isPrivate,
        startSeconds = seconds
    ) ?: return
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(video.summary.title.resolve(locale), url))
}

@Composable
private fun LiveWatchRoute(
    container: ViewerContainer,
    id: String,
    inPip: Boolean,
    onPipEligible: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    openPage: (String) -> Unit,
    rememberPlayer: @Composable () -> Player?,
    onNavigateBack: () -> Unit
) {
    val factory = LiveWatchViewModel.Factory(id, container.live, container.catalog) {
        container.blockStore.isChannelBlocked(it.channelId, it.channelName)
    }
    val vm: LiveWatchViewModel = viewModel(key = id, factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val blockedEntries by container.blockStore.entries.collectAsStateWithLifecycle()
    val filteredState = remember(state, blockedEntries) {
        when (val s = state) {
            is LiveWatchUiState.Content -> s.copy(
                content = s.content.copy(
                    messages = container.blockStore.filterMessages(s.content.messages),
                    pinned = s.content.pinned?.takeUnless { container.blockStore.isChatUserBlocked(it.userId, it.authorName) }
                )
            )
            else -> s
        }
    }
    val currentStream = (filteredState as? LiveWatchUiState.Content)?.content?.stream
    val isChannelBlocked = remember(currentStream, blockedEntries) {
        currentStream != null &&
            container.blockStore.isChannelBlocked(currentStream.channelId, currentStream.channelName)
    }
    val player = rememberPlayer()
    var fullscreen by remember { mutableStateOf(false) }
    val locale = currentLocale()
    PipEligibility(player, onPipEligible)
    LaunchedEffect(fullscreen) { onFullscreen(fullscreen) }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    DisposableEffect(Unit) { onDispose { onFullscreen(false) } }
    RunLive(vm, player)
    val safety = remember(locale.language, player) {
        LiveSafetyActions(
            onReportStream = {
                openPage(ViewerReportUrls.liveStreamUrl(it.id, locale.language, BuildConfig.WEB_HOST))
            },
            onReportChannel = {
                val channelId = it.channelId?.takeIf { id -> id.isNotBlank() }
                if (channelId != null) {
                    openPage(ViewerReportUrls.channelUrl(channelId, locale.language, BuildConfig.WEB_HOST))
                }
            },
            onBlockChannel = {
                player?.stop()
                player?.clearMediaItems()
                container.blockStore.blockChannel(it.channelId, it.channelName)
                onNavigateBack()
            },
            onReportMessage = { stream, msg ->
                openPage(ViewerReportUrls.liveChatUrl(stream.id, msg.id, locale.language, BuildConfig.WEB_HOST))
            },
            onBlockUser = { container.blockStore.blockChatUser(it.userId, it.authorName) }
        )
    }
    LiveWatchScreen(
        state = filteredState,
        player = player,
        isFullscreen = fullscreen,
        isInPictureInPicture = inPip,
        onToggleFullscreen = { fullscreen = !fullscreen },
        onReturnToLive = {
            val moment = vm.returnToLive()
            player?.let { if (moment == null) it.seekToDefaultPosition() else it.seekTo(moment) }
            player?.play()
        },
        onRetry = vm::load,
        safety = safety,
        isChannelBlocked = isChannelBlocked,
        onUnblockChannel = currentStream?.let { s -> { container.blockStore.unblockChannel(s.channelId, s.channelName) } }
    )
}

/** Keeps PiP eligibility in sync with playback while the watch page is shown. */
@Composable
private fun PipEligibility(player: Player?, onPipEligible: (Boolean) -> Unit) {
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = onPipEligible(isPlaying)
        }
        player?.addListener(listener)
        onPipEligible(player?.isPlaying == true)
        onDispose {
            player?.removeListener(listener)
            onPipEligible(false)
        }
    }
}
