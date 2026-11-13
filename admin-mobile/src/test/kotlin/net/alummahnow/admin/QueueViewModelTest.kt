package net.alummahnow.admin

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.admin.queue.QueueViewModel
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.admin.FixtureReviewService
import net.alummahnow.core.bridge.admin.ReviewDecision
import net.alummahnow.core.bridge.admin.ReviewItem
import net.alummahnow.core.bridge.admin.ReviewTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        AunLog.enabled = false
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `action needed is sorted by escalation, then SLA deadline, then risk`() {
        val t = Instant.parse("2026-11-13T12:00:00Z")
        val items = listOf(
            ReviewItem("late", "s", slaDueAt = t.plusSeconds(600), risk = 0.9),
            ReviewItem("soon-low", "s", slaDueAt = t.plusSeconds(60), risk = 0.1),
            ReviewItem("soon-high", "s", slaDueAt = t.plusSeconds(60), risk = 0.8),
            ReviewItem("escalated", "s", slaDueAt = t.plusSeconds(900), escalate = true),
            ReviewItem("no-sla", "s", risk = 1.0)
        )
        assertEquals(
            listOf("escalated", "soon-high", "soon-low", "late", "no-sla"),
            QueueViewModel.sort(ReviewTab.ACTION_NEEDED, items).map { it.id }
        )
    }

    @Test fun `loads the fixture queue, and a reviewer never sees minor items`() = runTest(dispatcher) {
        val vm = QueueViewModel(FixtureReviewService(), isSenior = false)
        advanceUntilIdle()
        val ids = vm.state.value.items.map { it.id }
        assertEquals("fx-nsfw", ids.first())
        assertFalse("fx-minor" in ids)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `a senior sees the minor item first (escalated)`() = runTest(dispatcher) {
        val vm = QueueViewModel(FixtureReviewService(FixtureReviewService.Role.SENIOR), isSenior = true)
        advanceUntilIdle()
        assertEquals("fx-minor", vm.state.value.items.first().id)
    }

    @Test fun `search is live and debounced`() = runTest(dispatcher) {
        val vm = QueueViewModel(FixtureReviewService(), isSenior = false)
        advanceUntilIdle()
        vm.setQuery("char")
        advanceTimeBy(QueueViewModel.SEARCH_DEBOUNCE_MS / 2)
        assertEquals(4, vm.state.value.items.size)
        advanceUntilIdle()
        assertEquals(listOf("fx-risk"), vm.state.value.items.map { it.id })
    }

    @Test fun `offline keeps the last text-only list and says so`() = runTest(dispatcher) {
        val fixture = FixtureReviewService()
        val vm = QueueViewModel(fixture, isSenior = false)
        advanceUntilIdle()
        fixture.offline = true
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.offline)
        assertEquals(4, vm.state.value.items.size)
    }

    @Test fun `a decision leaves Action needed at once, its undo brings it back`() = runTest(dispatcher) {
        val fixture = FixtureReviewService()
        val vm = QueueViewModel(fixture, isSenior = false)
        advanceUntilIdle()
        val decided = fixture.decide("fx-risk", ReviewDecision.APPROVE, null, null, 1)
        vm.replace(decided)
        assertFalse(vm.state.value.items.any { it.id == "fx-risk" })
        vm.replace(fixture.decide("fx-risk", ReviewDecision.UNDO, null, null, decided.version))
        assertTrue(vm.state.value.items.any { it.id == "fx-risk" })
    }

    @Test fun `history lists decided items newest first`() = runTest(dispatcher) {
        val vm = QueueViewModel(FixtureReviewService(), isSenior = false)
        vm.setTab(ReviewTab.HISTORY)
        advanceUntilIdle()
        assertEquals(listOf("fx-old"), vm.state.value.items.map { it.id })
    }
}
