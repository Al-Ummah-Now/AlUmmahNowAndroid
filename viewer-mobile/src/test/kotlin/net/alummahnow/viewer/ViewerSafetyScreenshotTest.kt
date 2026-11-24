package net.alummahnow.viewer

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.safety.BlockedEntry
import net.alummahnow.core.bridge.safety.BlockedKind
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.home.HomeScreen
import net.alummahnow.feature.home.HomeUiState
import net.alummahnow.feature.live.LiveSafetyActions
import net.alummahnow.feature.live.LiveWatchScreen
import net.alummahnow.feature.live.LiveWatchUiState
import net.alummahnow.feature.watch.WatchSafetyActions
import net.alummahnow.feature.watch.WatchScreen
import net.alummahnow.feature.watch.WatchUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Whole-screen frames (menus and dialogs are windows of their own), in the frame's language and theme. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ViewerSafetyScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private enum class Look(val ar: Boolean, val mode: ThemeMode, val file: String) {
        EN_DARK(false, ThemeMode.DARK, "en_dark"),
        EN_LIGHT(false, ThemeMode.LIGHT, "en_light"),
        AR_DARK(true, ThemeMode.DARK, "ar_dark"),
        AR_LIGHT(true, ThemeMode.LIGHT, "ar_light")
    }

    private fun shoot(
        name: String,
        look: Look,
        tablet: Boolean = false,
        fontScale: Float = 1f,
        taps: List<String> = emptyList(),
        content: @Composable () -> Unit
    ) {
        val device = if (tablet) "w1024dp-h640dp-land-xhdpi" else "w411dp-h891dp-port-xxhdpi"
        RuntimeEnvironment.setQualifiers("${if (look.ar) "ar-ldrtl" else "en-ldltr"}-$device")
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AunTheme(mode = look.mode) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        taps.forEach {
            compose.onNodeWithTag(it).performClick()
            compose.waitForIdle()
        }
        val size = if (fontScale == 1f) "" else "_200pct"
        captureScreenRoboImage("src/test/screenshots/${name}_${look.file}_${if (tablet) "tablet" else "phone"}$size.png")
    }

    private fun channel(look: Look) = if (look.ar) "قناة النور" else "Example Channel"

    private fun watch(look: Look, blocked: Boolean = false) = @Composable {
        WatchScreen(
            state = WatchUiState.Content(
                sampleSafetyDetail(channel(look), title = if (look.ar) "تلاوة هادئة" else "A calm recitation"),
                emptyList()
            ),
            player = null,
            isFullscreen = false,
            isInPictureInPicture = false,
            onToggleFullscreen = {},
            onVideoClick = {},
            onRetry = {},
            safety = WatchSafetyActions({}, {}, {}),
            isChannelBlocked = blocked,
            onUnblockChannel = {}
        )
    }

    private fun live(look: Look, blocked: Boolean = false) = @Composable {
        val message = if (look.ar) {
            LiveChatMessage("m-9", "ضيف", "السلام عليكم", 1_000L, userId = "u-9")
        } else {
            LiveChatMessage("m-9", "Guest viewer", "Assalamu alaikum", 1_000L, userId = "u-9")
        }
        val content = sampleSafetyLiveContent(channel(look), messages = listOf(message))
        LiveWatchScreen(
            state = LiveWatchUiState.Content(
                content.copy(stream = content.stream.copy(title = if (look.ar) "بث مباشر" else "Live stream"))
            ),
            player = null,
            isFullscreen = false,
            isInPictureInPicture = false,
            onToggleFullscreen = {},
            onReturnToLive = {},
            onRetry = {},
            safety = LiveSafetyActions({}, {}, {}, { _, _ -> }, {}),
            isChannelBlocked = blocked,
            onUnblockChannel = {}
        )
    }

    private fun home(allBlocked: Boolean) = @Composable {
        HomeScreen(HomeUiState.Content(emptyList()), {}, {}, {}, allBlocked = allBlocked)
    }

    private fun blockedList(look: Look, long: Boolean = false, empty: Boolean = false) = @Composable {
        val entries = when {
            empty -> emptyList()
            long && look.ar -> listOf(
                BlockedEntry(
                    "channel:id:ch-1",
                    BlockedKind.CHANNEL,
                    "قناة الخطب الأسبوعية والبثوث المباشرة من المسجد الكبير في وسط المدينة القديمة"
                ),
                BlockedEntry("channel:id:ch-2", BlockedKind.CHANNEL, ""),
                BlockedEntry("user:id:u-1", BlockedKind.CHAT_USER, "عبد الرحمن بن عبد الله المنصوري")
            )
            long -> listOf(
                BlockedEntry(
                    "channel:id:ch-1",
                    BlockedKind.CHANNEL,
                    "Weekly sermons and live streams from the great mosque in the middle of the old city"
                ),
                BlockedEntry("channel:id:ch-2", BlockedKind.CHANNEL, ""),
                BlockedEntry("user:id:u-1", BlockedKind.CHAT_USER, "Abdurrahman ibn Abdullah Almansouri")
            )
            else -> listOf(
                BlockedEntry("channel:id:ch-1", BlockedKind.CHANNEL, channel(look)),
                BlockedEntry("user:id:u-1", BlockedKind.CHAT_USER, if (look.ar) "ضيف" else "Guest viewer")
            )
        }
        BlockedListDialog(entries, onUnblock = {}, onDismiss = {})
    }

    private fun settings(look: Look) = @Composable {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                DisplaySettingsSheetContent(
                    theme = look.mode,
                    language = if (look.ar) "ar" else "en",
                    languageSwitchSupported = true,
                    onTheme = {},
                    onLanguage = {},
                    onDismiss = {},
                    onOpenUrl = {},
                    onOpenBlockedList = {},
                    webHost = "videos.qa.alummahnow.net"
                )
            }
        }
    }

    private fun watchMenu(look: Look) = shoot("watch_safety_menu", look, taps = WATCH_MENU, content = watch(look))

    private fun watchConfirm(look: Look, fontScale: Float = 1f) =
        shoot("watch_block_confirm", look, fontScale = fontScale, taps = WATCH_MENU + "watch-block-channel-item", content = watch(look))

    private fun watchBlocked(look: Look) = shoot("watch_blocked", look, content = watch(look, blocked = true))

    private fun liveMenu(look: Look) = shoot("live_safety_menu", look, taps = LIVE_MENU, content = live(look))

    private fun liveConfirm(look: Look) =
        shoot("live_block_confirm", look, taps = LIVE_MENU + "live-block-channel-item", content = live(look))

    private fun chatMenu(look: Look, fontScale: Float = 1f) =
        shoot("live_chat_menu", look, fontScale = fontScale, taps = CHAT_MENU, content = live(look))

    private fun chatConfirm(look: Look) =
        shoot("live_chat_block_confirm", look, taps = CHAT_MENU + "live-block-user-item", content = live(look))

    private fun liveBlocked(look: Look) = shoot("live_blocked", look, content = live(look, blocked = true))

    private fun homeBlocked(look: Look, fontScale: Float = 1f) =
        shoot("home_all_blocked", look, fontScale = fontScale, content = home(allBlocked = true))

    private fun list(look: Look, tablet: Boolean = false, fontScale: Float = 1f) =
        shoot("blocked_list", look, tablet, fontScale, content = blockedList(look))

    private fun listLong(look: Look) = shoot("blocked_list_long_names", look, content = blockedList(look, long = true))

    private fun listEmpty(look: Look) = shoot("blocked_list_empty", look, content = blockedList(look, empty = true))

    private fun settingsRow(look: Look) = shoot("settings_blocked_row", look, content = settings(look))

    @Test fun watchMenuEnDark() = watchMenu(Look.EN_DARK)

    @Test fun watchMenuEnLight() = watchMenu(Look.EN_LIGHT)

    @Test fun watchMenuArDark() = watchMenu(Look.AR_DARK)

    @Test fun watchMenuArLight() = watchMenu(Look.AR_LIGHT)

    @Test fun watchConfirmEnDark() = watchConfirm(Look.EN_DARK)

    @Test fun watchConfirmEnLight() = watchConfirm(Look.EN_LIGHT)

    @Test fun watchConfirmArDark() = watchConfirm(Look.AR_DARK)

    @Test fun watchConfirmArLight() = watchConfirm(Look.AR_LIGHT)

    @Test fun watchConfirmEnDark200() = watchConfirm(Look.EN_DARK, 2f)

    @Test fun watchConfirmArLight200() = watchConfirm(Look.AR_LIGHT, 2f)

    @Test fun watchBlockedEnDark() = watchBlocked(Look.EN_DARK)

    @Test fun watchBlockedArLight() = watchBlocked(Look.AR_LIGHT)

    @Test fun liveMenuEnDark() = liveMenu(Look.EN_DARK)

    @Test fun liveMenuArLight() = liveMenu(Look.AR_LIGHT)

    @Test fun liveConfirmEnLight() = liveConfirm(Look.EN_LIGHT)

    @Test fun liveConfirmArDark() = liveConfirm(Look.AR_DARK)

    @Test fun chatMenuEnDark() = chatMenu(Look.EN_DARK)

    @Test fun chatMenuArLight() = chatMenu(Look.AR_LIGHT)

    @Test fun chatMenuArDark200() = chatMenu(Look.AR_DARK, 2f)

    @Test fun chatConfirmEnDark() = chatConfirm(Look.EN_DARK)

    @Test fun chatConfirmArLight() = chatConfirm(Look.AR_LIGHT)

    @Test fun liveBlockedEnLight() = liveBlocked(Look.EN_LIGHT)

    @Test fun liveBlockedArDark() = liveBlocked(Look.AR_DARK)

    @Test fun homeBlockedEnDark() = homeBlocked(Look.EN_DARK)

    @Test fun homeBlockedArLight() = homeBlocked(Look.AR_LIGHT)

    @Test fun homeBlockedArDark200() = homeBlocked(Look.AR_DARK, 2f)

    @Test fun homeEmptyEnDark() = shoot("home_empty", Look.EN_DARK, content = home(allBlocked = false))

    @Test fun listEnDark() = list(Look.EN_DARK)

    @Test fun listEnLight() = list(Look.EN_LIGHT)

    @Test fun listArDark() = list(Look.AR_DARK)

    @Test fun listArLight() = list(Look.AR_LIGHT)

    @Test fun listEnDarkTablet() = list(Look.EN_DARK, tablet = true)

    @Test fun listArLightTablet() = list(Look.AR_LIGHT, tablet = true)

    @Test fun listEnDark200() = list(Look.EN_DARK, fontScale = 2f)

    @Test fun listArLight200() = list(Look.AR_LIGHT, fontScale = 2f)

    @Test fun listLongEnLight() = listLong(Look.EN_LIGHT)

    @Test fun listLongArDark() = listLong(Look.AR_DARK)

    @Test fun listEmptyEnDark() = listEmpty(Look.EN_DARK)

    @Test fun listEmptyArLight() = listEmpty(Look.AR_LIGHT)

    @Test fun settingsEnDark() = settingsRow(Look.EN_DARK)

    @Test fun settingsArLight() = settingsRow(Look.AR_LIGHT)

    private companion object {
        val WATCH_MENU = listOf("watch-more-actions")
        val LIVE_MENU = listOf("live-more-actions")
        val CHAT_MENU = listOf("live-chat-more-m-9")
    }
}
