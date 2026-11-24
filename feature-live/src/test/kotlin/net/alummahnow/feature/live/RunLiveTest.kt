package net.alummahnow.feature.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.live.LiveState
import net.alummahnow.core.bridge.live.LiveStatus
import net.alummahnow.fixtures.PausedPlayer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunLiveTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the player connecting does not count the viewer out, leaving does`() {
        AunLog.enabled = false
        val live = FakeLive(LiveState(LiveStatus.LIVE, playbackUrl = "https://x.test/p.m3u8"))
        val vm = LiveWatchViewModel("s", live, FakeCatalog())
        var player by mutableStateOf<Player?>(null)
        var isShown by mutableStateOf(true)
        compose.setContent { if (isShown) RunLive(vm, player) }
        compose.waitUntil { live.beats == listOf(false) }
        player = PausedPlayer("live:s:live", 600_000)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(listOf(false), live.beats)
        isShown = false
        compose.waitUntil { live.beats == listOf(false, true) }
    }
}
