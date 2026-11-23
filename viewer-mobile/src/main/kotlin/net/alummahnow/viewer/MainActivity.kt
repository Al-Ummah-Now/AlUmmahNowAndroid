package net.alummahnow.viewer

import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.links.AppLink
import net.alummahnow.core.bridge.links.AppLinkParser
import net.alummahnow.design.format.displayLocale
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.feature.home.HomeScreen
import net.alummahnow.feature.home.HomeViewModel
import net.alummahnow.feature.watch.WatchScreen
import net.alummahnow.feature.watch.WatchViewModel
import net.alummahnow.player.ui.rememberMediaController

class MainActivity : ComponentActivity() {
    /** True while a video is playing on the watch page, so leaving the app enters picture-in-picture. */
    private var pipEligible = false
    private val inPip = mutableStateOf(false)

    /** Videos and Shorts opened from a link (App Links or `alummahnow://watch/{id}`), delivered once each. */
    private val links = Channel<String>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pipListener = Consumer<PictureInPictureModeChangedInfo> { inPip.value = it.isInPictureInPictureMode }
        addOnPictureInPictureModeChangedListener(pipListener)
        val container = (application as AunViewerApp).container
        ViewerNotificationChannels.ensureAll(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val theme by container.displayPrefs.theme.collectAsState(initial = null)
            val deviceDark = isSystemInDarkTheme()
            LaunchedEffect(Unit) { container.displayPrefs.adoptDeviceMode(deviceDark) }
            AunTheme(mode = theme) {
                ViewerNavHost(container, inPip.value, links.receiveAsFlow(), onPipEligible = ::setPipEligible)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        when (val link = AppLinkParser.parse(url, BuildConfig.WEB_HOST, BuildConfig.SHORT_LINK_HOST)) {
            is AppLink.Video -> links.trySend(link.id)
            is AppLink.Short -> links.trySend(link.id)
            // No native screen for these yet: show the page on the site instead of a dead end.
            is AppLink.Live, is AppLink.Playlist, is AppLink.Post, is AppLink.Channel, is AppLink.ChannelId -> openInBrowser(url)
            // The viewer has no sign-in; the app simply opens.
            is AppLink.AuthCallback, is AppLink.Studio, null -> Unit
        }
    }

    /**
     * Opens [url] in the browser by package, so the verified App Link does not resolve straight back to this app.
     * Does nothing when no browser is installed.
     */
    private fun openInBrowser(url: String) {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://${BuildConfig.WEB_HOST}/"))
        val browser = packageManager.queryIntentActivities(probe, 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != packageName }
            ?: return AunLog.w("links", "no browser for link")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser))
        } catch (e: ActivityNotFoundException) {
            AunLog.w("links", "browser refused link", "type" to e.javaClass.simpleName)
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
}

@Composable
private fun ViewerNavHost(container: AppContainer, inPip: Boolean, links: Flow<String>, onPipEligible: (Boolean) -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(nav) {
        links.collect { id ->
            // Ids are validated by AppLinkParser; a route that still fails is logged, never a crash.
            runCatching { nav.navigate(Routes.watch(id)) { launchSingleTop = true } }
                .onFailure { AunLog.w("links", "link not routed", "type" to it.javaClass.simpleName) }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    var notificationsOpen by remember { mutableStateOf(false) }
    var eventsOpen by remember { mutableStateOf(false) }
    var badgeCount by remember { mutableStateOf(0) }
    var eventCalendarEnabled by remember { mutableStateOf(false) }
    val theme by container.displayPrefs.theme.collectAsState(initial = null)

    LaunchedEffect(container) {
        runCatching { badgeCount = container.notifications.badge().visibleCount }
        runCatching { eventCalendarEnabled = container.events.viewerFeatures().eventCalendar }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container.catalog))
            val state by vm.state.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                onVideoClick = { nav.navigate(Routes.watch(it)) },
                onRetry = vm::load,
                onOpenSettings = { settingsOpen = true },
                notificationBadgeCount = badgeCount,
                onOpenNotifications = { notificationsOpen = true },
                onOpenEvents = if (eventCalendarEnabled) ({ eventsOpen = true }) else null
            )
        }
        composable(
            Routes.WATCH,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: WatchViewModel = viewModel(key = id, factory = WatchViewModel.Factory(container.catalog, id))
            val state by vm.state.collectAsStateWithLifecycle()
            val player by rememberMediaController()
            var fullscreen by remember { mutableStateOf(false) }
            val activity = context as MainActivity
            PipEligibility(player, onPipEligible)
            LaunchedEffect(fullscreen) { activity.setFullscreen(fullscreen) }
            BackHandler(enabled = fullscreen) { fullscreen = false }
            DisposableEffect(Unit) { onDispose { activity.setFullscreen(false) } }
            WatchScreen(
                state = state,
                player = player,
                isFullscreen = fullscreen,
                isInPictureInPicture = inPip,
                onToggleFullscreen = { fullscreen = !fullscreen },
                onVideoClick = { nav.navigate(Routes.watch(it)) },
                onRetry = vm::load,
                onShare = { video, positionSeconds ->
                    val lang = AppLanguage.current(context)
                    ShareShortLink.shareVideo(
                        context = context,
                        videoId = video.id,
                        title = video.summary.title.resolve(context.resources.displayLocale()),
                        startSeconds = positionSeconds,
                        lang = if (lang == "ar") "ar" else null
                    )
                }
            )
        }
    }
    if (notificationsOpen) {
        NotificationsSheet(
            repository = container.notifications,
            onBadgeChange = { badgeCount = it },
            onDismiss = { notificationsOpen = false }
        )
    }
    if (eventsOpen && eventCalendarEnabled) {
        EventsCalendarSheet(
            repository = container.events,
            onOpenEvent = { event ->
                eventsOpen = false
                val targetUrl = "${BuildConfig.WEB_BASE_URL}${event.watchPath}"
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                }
            },
            onDismiss = { eventsOpen = false }
        )
    }
    if (settingsOpen) {
        DisplaySettingsSheet(
            theme = theme,
            language = AppLanguage.current(context),
            languageSwitchSupported = AppLanguage.isSupported,
            onTheme = { scope.launch { container.displayPrefs.setTheme(it) } },
            onLanguage = { AppLanguage.set(context, it) },
            onDismiss = { settingsOpen = false }
        )
    }
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
