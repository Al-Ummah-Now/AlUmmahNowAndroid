package net.alummahnow.feature.live

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.live.LiveEventType
import net.alummahnow.core.bridge.live.LiveKind
import net.alummahnow.core.bridge.live.LiveState
import net.alummahnow.core.bridge.live.LiveStatus
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.fixtures.PausedPlayer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Live, a premiere's countdown, a recording and the list: both languages and modes, phone and tablet. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LiveScreenshotTest {
    private fun text(lang: Lang, en: String, ar: String) = if (lang == Lang.AR) ar else en

    private fun stream(lang: Lang) = LiveStream(
        "s",
        text(lang, "Questions after Maghrib", "أسئلة بعد المغرب"),
        LiveStatus.LIVE,
        description = text(lang, "Weekly. Questions are answered at the end.", "كل أسبوع. تُجاب الأسئلة في الآخر."),
        eventType = LiveEventType.HALQA,
        channelName = text(lang, "Noor Learning", "نور للتعلّم"),
        viewers = 128
    )

    private fun premiereStream(lang: Lang) = stream(lang).copy(
        id = "p",
        title = text(lang, "Tafsir of Surah Al-Kahf", "تفسير سورة الكهف"),
        status = LiveStatus.SCHEDULED,
        kind = LiveKind.PREMIERE,
        description = null,
        eventType = null,
        viewers = null
    )

    private fun messages(lang: Lang) = listOf(
        LiveChatMessage(
            "1",
            text(lang, "Yusuf Rahman", "يوسف الرحمن"),
            text(lang, "Assalamu alaikum from Toronto.", "السلام عليكم من تورنتو."),
            4_000
        ),
        LiveChatMessage(
            "2",
            text(lang, "Noor Learning", "نور للتعلّم"),
            text(lang, "Wa alaikum assalam. Welcome, everyone.", "وعليكم السلام ورحمة الله. أهلًا بكم جميعًا."),
            9_000,
            LiveChatMessage.Role.OWNER
        ),
        LiveChatMessage(
            "3",
            text(lang, "Omar Farooq", "عمر الفاروق"),
            text(lang, "The recording stays on the channel with the chat beside it.", "يبقى التسجيل في القناة ومعه المحادثة."),
            15_000,
            LiveChatMessage.Role.MODERATOR
        )
    )

    private fun live(lang: Lang) = LiveWatchContent(
        stream(lang),
        LiveState(LiveStatus.LIVE, eventType = LiveEventType.HALQA, chatEnabled = true),
        LiveMode.LIVE,
        viewers = 128,
        messages = messages(lang),
        pinned = LiveChatMessage(
            "0",
            text(lang, "Noor Learning", "نور للتعلّم"),
            text(lang, "Please keep to today’s subject.", "نرجو الالتزام بموضوع اليوم."),
            0
        )
    )

    private fun coming(lang: Lang) = LiveWatchContent(
        premiereStream(lang),
        LiveState(LiveStatus.SCHEDULED, kind = LiveKind.PREMIERE, chatEnabled = true),
        LiveMode.SOON,
        untilStartMs = 7_954_000
    )

    private fun recording(lang: Lang) = LiveWatchContent(
        stream(lang).copy(status = LiveStatus.ENDED, eventType = null),
        LiveState(LiveStatus.ENDED, vodUrl = "x", chatReplay = true),
        LiveMode.RECORDING,
        messages = messages(lang)
    )

    private fun watch(content: LiveWatchContent): @Composable () -> Unit = {
        val player = remember { PausedPlayer("s", 600_000) }
        LiveWatchScreen(
            LiveWatchUiState.Content(content),
            player = player,
            isFullscreen = false,
            isInPictureInPicture = false,
            onToggleFullscreen = {},
            onReturnToLive = {},
            onRetry = {}
        )
    }

    private fun list(lang: Lang): @Composable () -> Unit = {
        val content = LiveListContent(listOf(stream(lang)), listOf(premiereStream(lang)), hasEvents = true)
        LiveListScreen(LiveListUiState.Content(content), onOpen = {}, onEvent = {}, onRetry = {})
    }

    private fun rail(lang: Lang): @Composable () -> Unit = {
        Surface(color = MaterialTheme.colorScheme.background) {
            LiveRail(listOf(stream(lang), premiereStream(lang)), onOpen = {}, onSeeAll = {}, cardWidth = 260.dp)
        }
    }

    private fun all(device: Device, lang: Lang, mode: ThemeMode) {
        shoot("live", lang, mode, device, watch(live(lang)))
        shoot("live_behind", lang, mode, device, watch(live(lang).copy(isBehind = true)))
        shoot("premiere", lang, mode, device, watch(coming(lang)))
        shoot("recording", lang, mode, device, watch(recording(lang)))
        shoot("list", lang, mode, device, list(lang))
        shoot("rail", lang, mode, device, rail(lang))
    }

    @Test fun phoneEnDark() = all(Device.PHONE, Lang.EN, ThemeMode.DARK)

    @Test fun phoneEnLight() = all(Device.PHONE, Lang.EN, ThemeMode.LIGHT)

    @Test fun phoneArDark() = all(Device.PHONE, Lang.AR, ThemeMode.DARK)

    @Test fun phoneArLight() = all(Device.PHONE, Lang.AR, ThemeMode.LIGHT)

    @Test fun tabletEnDark() = all(Device.TABLET, Lang.EN, ThemeMode.DARK)

    @Test fun tabletArDark() = all(Device.TABLET, Lang.AR, ThemeMode.DARK)

    @Test fun tabletArLight() = all(Device.TABLET, Lang.AR, ThemeMode.LIGHT)

    @Test fun tabletEnLight() = all(Device.TABLET, Lang.EN, ThemeMode.LIGHT)

    /** A large tablet held upright: the chat is under the player. */
    @Test fun portraitTablet() = Lang.entries.forEach { lang ->
        listOf(ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
            shoot("live", lang, mode, Device.TABLET_PORTRAIT, watch(live(lang)))
            shoot("recording", lang, mode, Device.TABLET_PORTRAIT, watch(recording(lang)))
        }
    }
}
