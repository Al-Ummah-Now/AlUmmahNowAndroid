package net.alummahnow.admin

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.admin.review.ReviewMessage
import net.alummahnow.admin.review.ReviewViewModel
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.admin.FixtureReviewService
import net.alummahnow.core.bridge.admin.ReviewDecision
import net.alummahnow.core.bridge.admin.ReviewItem
import net.alummahnow.core.bridge.admin.ReviewReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        AunLog.enabled = false
        Dispatchers.setMain(dispatcher)
    }

    /** The claim renews forever while an item is open; each test closes it, as leaving the screen does. */
    private val opened = mutableListOf<ReviewViewModel>()

    @After fun tearDown() = Dispatchers.resetMain()

    /** runTest drains the scheduler when the body ends, so the open items are closed inside it. */
    private fun reviewTest(body: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            opened.forEach { it.viewModelScope.cancel() }
        }
    }

    private fun vm(fixture: FixtureReviewService, id: String, claimed: MutableList<String> = mutableListOf()) =
        ReviewViewModel(fixture, id, "fx-reviewer", onClaimed = { claimed += it }).also { opened += it }

    @Test fun `opening an item claims it and renews the claim every minute`() = reviewTest {
        val fixture = FixtureReviewService()
        val claimed = mutableListOf<String>()
        val vm = vm(fixture, "fx-risk", claimed)
        runCurrent()
        assertEquals(listOf("fx-risk"), fixture.claims)
        assertEquals(listOf("fx-risk"), claimed)
        advanceTimeBy(ReviewViewModel.CLAIM_RENEW_MS + 1)
        assertEquals(2, fixture.claims.size)
        vm.decide(ReviewDecision.APPROVE, null, null, onStepUp = {}, onDone = {})
        runCurrent()
        advanceTimeBy(ReviewViewModel.CLAIM_RENEW_MS * 3)
        assertEquals("no renewals after the decision", 2, fixture.claims.size)
    }

    @Test fun `flagged frames come first and load into memory`() = reviewTest {
        val vm = vm(FixtureReviewService(), "fx-nsfw")
        runCurrent()
        val frames = vm.state.value.detail!!.frames
        assertTrue(frames.first().flagged)
        assertTrue(frames.drop(1).none { it.flagged })
        assertEquals(frames.size, vm.state.value.frames.size)
    }

    @Test fun `Show is audited first, and blurs again on reblur`() = reviewTest {
        val fixture = FixtureReviewService()
        val vm = vm(fixture, "fx-nsfw")
        runCurrent()
        assertFalse(vm.state.value.revealed)
        vm.reveal()
        runCurrent()
        assertEquals(listOf("fx-nsfw:item"), fixture.reveals)
        assertTrue(vm.state.value.revealed)
        vm.reblur()
        assertFalse(vm.state.value.revealed)
    }

    @Test fun `Show does nothing offline`() = reviewTest {
        val fixture = FixtureReviewService()
        val vm = vm(fixture, "fx-nsfw")
        runCurrent()
        fixture.offline = true
        vm.reveal()
        runCurrent()
        assertFalse(vm.state.value.revealed)
        assertEquals(ReviewMessage.Offline, vm.state.value.message)
    }

    @Test fun `a version conflict says who decided first`() = reviewTest {
        val vm = vm(FixtureReviewService(), "fx-conflict")
        runCurrent()
        var done: ReviewItem? = null
        vm.decide(ReviewDecision.APPROVE, null, null, onStepUp = {}, onDone = { done = it })
        runCurrent()
        val conflict = vm.state.value.message as ReviewMessage.Conflict
        assertEquals("approve", conflict.decision)
        assertEquals("Amina", conflict.byName)
        assertNull(done)
    }

    @Test fun `reject sends the reason and returns the decided row for the undo toast`() = reviewTest {
        val vm = vm(FixtureReviewService(), "fx-risk")
        runCurrent()
        var done: ReviewItem? = null
        vm.decide(ReviewDecision.REJECT, ReviewReason.SPAM_SCAM, "Fake appeal", onStepUp = {}, onDone = { done = it })
        runCurrent()
        assertEquals("rejected", done!!.status)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.isMine)
    }

    @Test fun `no decisions offline`() = reviewTest {
        val fixture = FixtureReviewService()
        val vm = vm(fixture, "fx-risk")
        runCurrent()
        fixture.offline = true
        var done = false
        vm.decide(ReviewDecision.APPROVE, null, null, onStepUp = {}, onDone = { done = true })
        runCurrent()
        assertFalse(done)
        assertEquals(ReviewMessage.Offline, vm.state.value.message)
    }
}
