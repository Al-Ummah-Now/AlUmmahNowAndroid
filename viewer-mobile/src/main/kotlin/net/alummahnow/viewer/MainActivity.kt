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
import androidx.activity.compose.LocalActivity
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
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.feature.home.HomeScreen
import net.alummahnow.feature.home.HomeViewModel
import net.alummahnow.feature.live.LiveListScreen
import net.alummahnow.feature.live.LiveListUiState
import net.alummahnow.feature.live.LiveListViewModel
import net.alummahnow.feature.live.LiveRail
import net.alummahnow.feature.live.LiveWatchScreen
import net.alummahnow.feature.live.LiveWatchViewModel
import net.alummahnow.feature.live.RunLive
import net.alummahnow.feature.watch.WatchScreen
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
private fun ViewerNavHost(container: AppContainer, inPip: Boolean, links: Flow<String>, onPipEligible: (Boolean) -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(nav) {
        links.collect { route ->
            // Ids are validated by AppLinkParser; a route that still fails is logged, never a crash.
            runCatching { nav.navigate(route) { launchSingleTop = true } }
                .onFailure { AunLog.w("links", "link not routed", "type" to it.javaClass.simpleName) }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    val theme by container.displayPrefs.theme.collectAsState(initial = null)

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container.catalog))
            val state by vm.state.collectAsStateWithLifecycle()
            val liveVm: LiveListViewModel = viewModel(factory = LiveListViewModel.Factory(container.live))
            val live by liveVm.state.collectAsStateWithLifecycle()
            HomeScreen(
                state,
                onVideoClick = { nav.navigate(Routes.watch(it)) },
                onRetry = {
                    vm.load()
                    liveVm.load()
                },
                onOpenSettings = { settingsOpen = true },
                liveRail = { width ->
                    LiveRail(
                        (live as? LiveListUiState.Content)?.list?.all.orEmpty(),
                        onOpen = { nav.navigate(Routes.live(it)) },
                        onSeeAll = { nav.navigate(Routes.LIVE_LIST) },
                        cardWidth = width
                    )
                }
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
                onRetry = vm::load
            )
        }
        composable(Routes.LIVE_LIST) {
            val vm: LiveListViewModel = viewModel(factory = LiveListViewModel.Factory(container.live))
            val state by vm.state.collectAsStateWithLifecycle()
            LiveListScreen(state, onOpen = { nav.navigate(Routes.live(it)) }, onEvent = vm::choose, onRetry = vm::load)
        }
        composable(Routes.LIVE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            LiveWatchRoute(container, entry.arguments?.getString("id").orEmpty(), inPip, onPipEligible)
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
            onOpenUrl = { url ->
                val activity = context as? MainActivity
                val opened = activity?.openInBrowser(url) == true
                if (!opened) {
                    android.widget.Toast.makeText(
                        context,
                        R.string.settings_no_browser,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

@Composable
private fun LiveWatchRoute(container: AppContainer, id: String, inPip: Boolean, onPipEligible: (Boolean) -> Unit) {
    val vm: LiveWatchViewModel = viewModel(key = id, factory = LiveWatchViewModel.Factory(id, container.live, container.catalog))
    val state by vm.state.collectAsStateWithLifecycle()
    val player by rememberMediaController()
    var fullscreen by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as? MainActivity
    PipEligibility(player, onPipEligible)
    LaunchedEffect(fullscreen) { activity?.setFullscreen(fullscreen) }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    DisposableEffect(Unit) { onDispose { activity?.setFullscreen(false) } }
    RunLive(vm, player)
    LiveWatchScreen(
        state = state,
        player = player,
        isFullscreen = fullscreen,
        isInPictureInPicture = inPip,
        onToggleFullscreen = { fullscreen = !fullscreen },
        onReturnToLive = {
            val moment = vm.returnToLive()
            player?.let { if (moment == null) it.seekToDefaultPosition() else it.seekTo(moment) }
            player?.play()
        },
        onRetry = vm::load
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
