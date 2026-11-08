package net.alummahnow.viewer

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.navigation.navDeepLink
import kotlinx.coroutines.launch
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.home.HomeScreen
import net.alummahnow.feature.home.HomeViewModel
import net.alummahnow.feature.watch.WatchScreen
import net.alummahnow.feature.watch.WatchViewModel
import net.alummahnow.player.ui.rememberMediaController

class MainActivity : ComponentActivity() {
    /** True while a video is playing on the watch page, so leaving the app enters picture-in-picture. */
    private var pipEligible = false
    private val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pipListener = Consumer<PictureInPictureModeChangedInfo> { inPip.value = it.isInPictureInPictureMode }
        addOnPictureInPictureModeChangedListener(pipListener)
        val container = (application as AunViewerApp).container
        setContent {
            val theme by container.displayPrefs.theme.collectAsState(initial = ThemeMode.SYSTEM)
            AunTheme(mode = theme) {
                ViewerNavHost(container, inPip.value, onPipEligible = ::setPipEligible)
            }
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
private fun ViewerNavHost(container: AppContainer, inPip: Boolean, onPipEligible: (Boolean) -> Unit) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    val theme by container.displayPrefs.theme.collectAsState(initial = ThemeMode.SYSTEM)

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container.catalog))
            val state by vm.state.collectAsStateWithLifecycle()
            HomeScreen(state, onVideoClick = {
                nav.navigate(Routes.watch(it))
            }, onRetry = vm::load, onOpenSettings = { settingsOpen = true })
        }
        composable(
            Routes.WATCH,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "alummahnow://watch/{id}" },
                navDeepLink { uriPattern = "https://videos.alummahnow.net/{lang}/watch/{id}" },
                navDeepLink { uriPattern = "https://videos.qa.alummahnow.net/{lang}/watch/{id}" }
            )
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
