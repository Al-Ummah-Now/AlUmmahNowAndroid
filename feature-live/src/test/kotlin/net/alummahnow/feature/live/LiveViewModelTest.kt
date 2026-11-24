package net.alummahnow.feature.live

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.live.LiveChatPage
import net.alummahnow.core.bridge.live.LiveEventType
import net.alummahnow.core.bridge.live.LiveFeatures
import net.alummahnow.core.bridge.live.LiveKind
import net.alummahnow.core.bridge.live.LiveState
import net.alummahnow.core.bridge.live.LiveStatus
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.design.components.ErrorKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = TestClock()

    @Before fun setUp() {
        AunLog.enabled = false
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun model(live: FakeLive) = LiveWatchViewModel("s", live, FakeCatalog()) { clock.at }

    private val LiveWatchViewModel.now get() = (state.value as LiveWatchUiState.Content).content

    private fun premiere(status: LiveStatus, video: String? = "v1", startedAt: Instant? = clock.at) =
        LiveState(status, kind = LiveKind.PREMIERE, premiereVideoId = video, startedAt = startedAt, durationSeconds = 600.0)

    private val onAir = LiveState(LiveStatus.LIVE, playbackUrl = "https://x.test/p.m3u8")

    @Test fun modes() {
        assertEquals(LiveMode.SOON, modeOf(LiveState(LiveStatus.SCHEDULED)))
        assertEquals(LiveMode.LIVE, modeOf(onAir))
        assertEquals(LiveMode.SOON, modeOf(LiveState(LiveStatus.LIVE)))
        assertEquals(LiveMode.ENDED, modeOf(LiveState(LiveStatus.ENDED)))
        assertEquals(LiveMode.RECORDING, modeOf(LiveState(LiveStatus.ENDED, vodUrl = "x")))
        assertEquals(LiveMode.CANCELLED, modeOf(LiveState(LiveStatus.CANCELLED)))
        assertEquals(LiveMode.LIVE, modeOf(premiere(LiveStatus.LIVE)))
        assertEquals(LiveMode.SOON, modeOf(premiere(LiveStatus.SCHEDULED)))
        assertEquals(LiveMode.RECORDING, modeOf(premiere(LiveStatus.ENDED)))
        assertEquals(LiveMode.ENDED, modeOf(premiere(LiveStatus.ENDED, video = null)))
    }

    @Test fun `counts down by the server's clock and asks often when it is due`() = test {
        val live = FakeLive(
            LiveState(LiveStatus.SCHEDULED, kind = LiveKind.PREMIERE, scheduledStart = clock.at.plusSeconds(100), clockOffsetMs = 10_000)
        )
        val vm = model(live)
        assertEquals(90_000L, vm.now.untilStartMs)
        clock.advance(14)
        vm.tick(null)
        assertEquals(1, live.stateReads)
        clock.advance(77)
        vm.tick(null)
        assertEquals(2, live.stateReads)
        assertTrue((vm.now.untilStartMs ?: 0) < 0)
        clock.advance(5)
        vm.tick(null)
        assertEquals(3, live.stateReads)
    }

    @Test fun `a premiere starts at its moment and goes on after it`() = test {
        val start = clock.at.minusSeconds(30)
        val live = FakeLive(premiere(LiveStatus.LIVE, startedAt = start))
        val vm = model(live)
        val media = vm.now.media as LiveMedia.Premiere
        assertEquals(30_000L, media.startMs)
        live.current = premiere(LiveStatus.ENDED, startedAt = start)
        clock.advance(61)
        vm.tick(null)
        assertEquals(LiveMode.RECORDING, vm.now.mode)
        // The same media: it does not start over
        assertSame(media, vm.now.media)
    }

    @Test fun `a video that is gone is said to be, and one not yet released is asked for again`() = test {
        assertEquals(LiveMode.ENDED, model(FakeLive(premiere(LiveStatus.ENDED, video = "nope"))).now.mode)
        val live = FakeLive(premiere(LiveStatus.LIVE, video = "nope"))
        val vm = model(live)
        assertNull(vm.now.media)
        live.current = premiere(LiveStatus.LIVE)
        clock.advance(5)
        vm.tick(null)
        assertTrue(vm.now.media is LiveMedia.Premiere)
    }

    @Test fun `going back is behind until returning to the moment`() = test {
        val vm = model(FakeLive(premiere(LiveStatus.LIVE, startedAt = clock.at.minusSeconds(100))))
        vm.follow(PlayerReading(100_000, isPlaying = true, canKeepUp = true, speed = 1f))
        assertEquals(emptyList<FollowAction>(), vm.follow(PlayerReading(40_000, isPlaying = true, canKeepUp = true, speed = 1f)))
        assertTrue(vm.now.isBehind)
        assertEquals(100_000L, vm.returnToLive())
        assertFalse(vm.now.isBehind)
    }

    @Test fun `a stream is behind when its newest moment is far ahead`() = test {
        val vm = model(FakeLive(onAir))
        vm.follow(PlayerReading(0, isPlaying = true, canKeepUp = true, speed = 1f, behindLiveMs = 12_000))
        assertFalse(vm.now.isBehind)
        vm.follow(PlayerReading(0, isPlaying = true, canKeepUp = true, speed = 1f, behindLiveMs = 90_000))
        assertTrue(vm.now.isBehind)
        assertNull(vm.returnToLive())
    }

    @Test fun `leaving is counted, and coming back at once`() = test {
        val live = FakeLive(onAir)
        val vm = model(live)
        repeat(2) {
            val running = launch { vm.run({ null }, {}) }
            runCurrent()
            running.cancelAndJoin()
        }
        assertEquals(listOf(false, true, false, true), live.beats)
    }

    @Test fun `following does not wait for a slow answer`() = test {
        val live = FakeLive(premiere(LiveStatus.LIVE, startedAt = clock.at.minusSeconds(100)))
        val vm = model(live)
        clock.advance(61)
        live.stateGate = CompletableDeferred()
        val actions = mutableListOf<FollowAction>()
        val running = launch {
            vm.run({ PlayerReading(150_000, isPlaying = true, canKeepUp = true, speed = 1f) }, { actions += it })
        }
        runCurrent()
        assertEquals(2, live.stateReads)
        assertEquals(listOf<FollowAction>(FollowAction.Seek(161_000)), actions)
        running.cancelAndJoin()
    }

    @Test fun `chat is read only while the server says so`() = test {
        val live = FakeLive(onAir)
        val vm = model(live)
        vm.tick(0)
        assertEquals(0, live.chatReads)
        assertFalse(vm.now.hasChat)
        live.current = onAir.copy(chatEnabled = true)
        clock.advance(60)
        vm.tick(0)
        vm.tick(0)
        assertEquals(1, live.chatReads)
        assertEquals(1, vm.now.messages.size)
        clock.advance(10)
        vm.tick(0)
        assertEquals(2, live.chatReads)
        // Turned off: gone from the screen at the next read of the state
        live.current = onAir
        clock.advance(60)
        vm.tick(0)
        assertTrue(vm.now.messages.isEmpty())
    }

    @Test fun `a chat that cannot be read is asked for once a minute`() = test {
        val live = FakeLive(onAir.copy(chatEnabled = true)).apply { chatFails = true }
        val vm = model(live)
        vm.tick(0)
        clock.advance(59)
        vm.tick(0)
        assertEquals(1, live.chatReads)
        clock.advance(1)
        vm.tick(0)
        assertEquals(2, live.chatReads)
    }

    @Test fun `the server says when to ask again`() = test {
        val live = FakeLive(onAir.copy(chatEnabled = true, pollAfterMs = 120_000)).apply { chatPollAfter = 30_000 }
        val vm = model(live)
        vm.tick(0)
        clock.advance(29)
        vm.tick(0)
        assertEquals(1, live.chatReads)
        clock.advance(1)
        vm.tick(0)
        assertEquals(2, live.chatReads)
        // The state's own 60 seconds are over
        clock.advance(89)
        vm.tick(0)
        assertEquals(1, live.stateReads)
        clock.advance(1)
        live.current = onAir.copy(chatEnabled = true)
        vm.tick(0)
        assertEquals(2, live.stateReads)
        // It says nothing any more: this app's interval again
        clock.advance(60)
        vm.tick(0)
        assertEquals(3, live.stateReads)
    }

    @Test fun `a refusal says when to ask again`() = test {
        val live = FakeLive(onAir.copy(chatEnabled = true))
        val vm = model(live)
        live.retryAfter = 200_000
        vm.tick(0)
        clock.advance(60)
        vm.tick(0)
        val asked = listOf(live.stateReads, live.chatReads, live.beats.size)
        clock.advance(139)
        vm.tick(0)
        assertEquals(asked, listOf(live.stateReads, live.chatReads, live.beats.size))
        clock.advance(61)
        vm.tick(0)
        assertEquals(asked.map { it + 1 }, listOf(live.stateReads, live.chatReads, live.beats.size))
    }

    @Test fun `a wait begins when the answer is there`() = test {
        val live = FakeLive(onAir.copy(chatEnabled = true))
        val vm = model(live)
        live.answering = { clock.advance(8) }
        vm.tick(0)
        clock.advance(60)
        // The state, the chat and the beat, 8 seconds each
        vm.tick(0)
        live.answering = {}
        val asked = listOf(live.stateReads, live.chatReads, live.beats.size)
        suspend fun after(seconds: Long): List<Int> {
            clock.advance(seconds)
            vm.tick(0)
            return listOf(live.stateReads, live.chatReads, live.beats.size)
        }
        // The chat's 10 seconds began 8 before the beat's 20, and the state's 60 8 before that
        assertEquals(asked[1], after(1)[1])
        assertEquals(asked[1] + 1, after(1)[1])
        assertEquals(asked[2], after(17)[2])
        assertEquals(asked[2] + 1, after(1)[2])
        assertEquals(asked[0], after(23)[0])
        assertEquals(asked[0] + 1, after(1)[0])
    }

    @Test fun `going to the background is not a failure`() = test {
        val live = FakeLive(onAir.copy(chatEnabled = true))
        val vm = model(live)
        live.chatGate = CompletableDeferred()
        val running = launch { vm.run({ null }, {}) }
        runCurrent()
        assertEquals(1, live.chatReads)
        running.cancelAndJoin()
        live.chatGate = null
        vm.tick(0)
        // Asked at once, not after a failure's minute
        assertEquals(2, live.chatReads)
        clock.advance(60)
        live.stateGate = CompletableDeferred()
        val again = launch { vm.run({ null }, {}) }
        runCurrent()
        assertEquals(2, live.stateReads)
        again.cancelAndJoin()
        live.stateGate = null
        vm.tick(0)
        assertEquals(3, live.stateReads)
    }

    @Test fun `a premiere's video that could not be read is asked for again`() = test {
        val live = FakeLive(premiere(LiveStatus.ENDED))
        val vm = LiveWatchViewModel("s", live, FakeCatalog(error = CoreError.Http(503))) { clock.at }
        assertEquals(LiveMode.RECORDING, vm.now.mode)
        clock.advance(15)
        vm.tick(null)
        assertEquals(2, live.stateReads)
    }

    @Test fun `what the server says is kept between 5 seconds and 10 minutes`() {
        assertNull(pollAfter(null))
        assertEquals(listOf(5L, 5L, 7L, 600L), listOf(-1L, 1L, 7_000L, Long.MAX_VALUE).map { pollAfter(it)?.seconds })
    }

    @Test fun `the replay is what had been written by then`() = test {
        val live = FakeLive(LiveState(LiveStatus.ENDED, vodUrl = "https://x.test/v.m3u8", chatReplay = true))
        live.windows[0] = LiveChatPage(listOf(LiveChatMessage("a", "A", "x", 0), LiveChatMessage("b", "A", "y", 5_000)))
        live.windows[60_000] = LiveChatPage(listOf(LiveChatMessage("c", "A", "z", 61_000)))
        val vm = model(live)
        vm.tick(0)
        assertEquals(listOf("a"), vm.now.messages.map { it.id })
        vm.tick(62_000)
        assertEquals(listOf("a", "b", "c"), vm.now.messages.map { it.id })
    }

    @Test fun `the list shows what comes next and only with its switches`() = test {
        val live = FakeLive()
        live.lists[LiveStatus.LIVE] = listOf(LiveStream("on", "", LiveStatus.LIVE))
        live.lists[LiveStatus.SCHEDULED] = listOf(
            LiveStream("later", "", LiveStatus.SCHEDULED, scheduledStart = clock.at.plusSeconds(900)),
            LiveStream("past", "", LiveStatus.SCHEDULED, scheduledStart = clock.at.minusSeconds(1)),
            LiveStream("untimed", "", LiveStatus.SCHEDULED),
            LiveStream("next", "", LiveStatus.SCHEDULED, scheduledStart = clock.at.plusSeconds(60))
        )
        val vm = LiveListViewModel(live) { clock.at }
        fun list() = (vm.state.value as LiveListUiState.Content).list
        assertEquals(listOf("on", "next", "later"), list().all.map { it.id })
        vm.choose(LiveEventType.JUMUAH)
        assertEquals(LiveEventType.JUMUAH, live.askedEvent)
        live.features = LiveFeatures()
        vm.load()
        assertEquals(listOf("on"), list().all.map { it.id })
        assertFalse(list().hasEvents)
        assertNull(list().event)
    }

    @Test fun `an earlier choice that answers late is not shown`() = test {
        val live = FakeLive()
        val vm = LiveListViewModel(live) { clock.at }
        val late = CompletableDeferred<Unit>()
        live.listGate = LiveEventType.JUMUAH to late
        vm.choose(LiveEventType.JUMUAH)
        vm.choose(LiveEventType.HALQA)
        late.complete(Unit)
        runCurrent()
        assertEquals(LiveEventType.HALQA, (vm.state.value as LiveListUiState.Content).list.event)
    }

    @Test fun `the replay begins without what was live and at its own time`() = test {
        val live = FakeLive(onAir.copy(chatEnabled = true)).apply { pinned = LiveChatMessage("p", "A", "pinned", 0) }
        val vm = model(live)
        vm.tick(0)
        assertEquals("p", vm.now.pinned?.id)
        clock.advance(59)
        // A live chat that could not be read waits a minute; the replay does not
        live.chatFails = true
        vm.tick(0)
        clock.advance(1)
        live.current = LiveState(LiveStatus.ENDED, vodUrl = "https://x.test/v.m3u8", chatReplay = true)
        live.windows[0] = LiveChatPage(listOf(LiveChatMessage("a", "A", "x", 0)))
        vm.tick(0)
        assertEquals(LiveMode.RECORDING, vm.now.mode)
        assertNull(vm.now.pinned)
        assertEquals(listOf("a"), vm.now.messages.map { it.id })
    }

    @Test fun `a failed refresh keeps the list, a failed first read says so`() = test {
        val live = FakeLive()
        val vm = LiveListViewModel(live) { clock.at }
        live.fails = true
        vm.load()
        assertTrue(vm.state.value is LiveListUiState.Content)
        assertEquals(LiveListUiState.Error(ErrorKind.GENERIC), LiveListViewModel(live) { clock.at }.state.value)
    }
}
