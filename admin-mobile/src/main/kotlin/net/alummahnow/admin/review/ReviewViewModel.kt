package net.alummahnow.admin.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.admin.ReviewDecision
import net.alummahnow.core.bridge.admin.ReviewDetail
import net.alummahnow.core.bridge.admin.ReviewError
import net.alummahnow.core.bridge.admin.ReviewItem
import net.alummahnow.core.bridge.admin.ReviewReason
import net.alummahnow.core.bridge.admin.ReviewService

/** Something the review screen tells the reviewer. */
sealed interface ReviewMessage {
    data class Conflict(val decision: String?, val byName: String?, val at: Instant?) : ReviewMessage
    data object Offline : ReviewMessage
    data object Failed : ReviewMessage
    data object Gone : ReviewMessage
}

data class ReviewUiState(
    val loading: Boolean = true,
    val detail: ReviewDetail? = null,
    val failed: Boolean = false,
    /** The item is shown unblurred (after an audited "Show"). Reset on leave, background and 60 s idle. */
    val revealed: Boolean = false,
    /** Someone else holds the claim. */
    val claimedBy: String? = null,
    val busy: Boolean = false,
    val message: ReviewMessage? = null,
    /** Frames as bytes in memory only (never a file, never a URL). */
    val frames: Map<String, ByteArray> = emptyMap()
)

/**
 * One review: claims it while open (3 minutes, renewed every minute), loads the frames into memory, reveals on an
 * audited "Show" and sends decisions with the item's version.
 */
class ReviewViewModel(
    private val review: ReviewService,
    private val reviewId: String,
    private val myUserId: String,
    private val onClaimed: (String) -> Unit = {}
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()
    private var claimJob: Job? = null

    init {
        load()
    }

    fun load() {
        mutableState.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            try {
                val detail = review.detail(reviewId)
                mutableState.update { it.copy(loading = false, detail = detail) }
                if (detail.item.isOpen) startClaim()
                loadFrames(detail)
            } catch (e: ReviewError.Offline) {
                AunLog.w("review", "offline", "type" to e.javaClass.simpleName)
                mutableState.update { it.copy(loading = false, failed = true, message = ReviewMessage.Offline) }
            } catch (e: CoreError) {
                AunLog.e("review", "detail failed", e)
                mutableState.update {
                    it.copy(loading = false, failed = true, message = if (e is CoreError.NotFound) ReviewMessage.Gone else null)
                }
            } catch (e: ReviewError) {
                AunLog.e("review", "detail refused", e)
                mutableState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun startClaim() {
        if (claimJob?.isActive == true) return
        claimJob = viewModelScope.launch {
            while (isActive) {
                try {
                    review.claim(reviewId)
                    mutableState.update { it.copy(claimedBy = null) }
                    onClaimed(reviewId)
                } catch (e: ReviewError.ClaimedByOther) {
                    mutableState.update { it.copy(claimedBy = e.name ?: "") }
                } catch (e: ReviewError) {
                    AunLog.w("review", "claim failed", "type" to e.javaClass.simpleName)
                } catch (e: CoreError) {
                    AunLog.w("review", "claim failed", "type" to e.javaClass.simpleName)
                }
                delay(CLAIM_RENEW_MS)
            }
        }
    }

    private suspend fun loadFrames(detail: ReviewDetail) {
        for (frame in detail.frames.take(MAX_FRAMES)) {
            val bytes = runCatching { review.media(reviewId, frame.key) }.getOrNull() ?: continue
            mutableState.update { it.copy(frames = it.frames + (frame.key to bytes)) }
        }
    }

    /** Audited reveal. Nothing is shown unless the server recorded it. */
    fun reveal(target: String = "item") {
        viewModelScope.launch {
            try {
                review.reveal(reviewId, target)
                mutableState.update { it.copy(revealed = true) }
            } catch (_: ReviewError.Offline) {
                mutableState.update { it.copy(message = ReviewMessage.Offline) }
            } catch (e: CoreError) {
                AunLog.e("review", "reveal failed", e)
                mutableState.update { it.copy(message = ReviewMessage.Failed) }
            } catch (e: ReviewError) {
                AunLog.e("review", "reveal refused", e)
                mutableState.update { it.copy(message = ReviewMessage.Failed) }
            }
        }
    }

    fun reblur() {
        mutableState.update { it.copy(revealed = false) }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    /**
     * Sends a decision. [onDone] gets the row as it is now (for the undo toast); [onStepUp] is called when the server
     * wants a fresh sign-in first.
     */
    fun decide(decision: ReviewDecision, reason: ReviewReason?, note: String?, onStepUp: () -> Unit, onDone: (ReviewItem) -> Unit) {
        val item = mutableState.value.detail?.item ?: return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = review.decide(reviewId, decision, reason, note, item.version)
                claimJob?.cancel()
                mutableState.update { it.copy(busy = false, revealed = false, detail = it.detail?.copy(item = result)) }
                onDone(result)
            } catch (e: ReviewError.AlreadyDecided) {
                claimJob?.cancel()
                mutableState.update { it.copy(busy = false, message = ReviewMessage.Conflict(e.decision, e.byName, e.at)) }
            } catch (_: ReviewError.StepUpRequired) {
                mutableState.update { it.copy(busy = false) }
                onStepUp()
            } catch (_: ReviewError.Offline) {
                mutableState.update { it.copy(busy = false, message = ReviewMessage.Offline) }
            } catch (e: ReviewError) {
                AunLog.e("review", "decision refused", e)
                mutableState.update { it.copy(busy = false, message = ReviewMessage.Failed) }
            } catch (e: CoreError) {
                AunLog.e("review", "decision failed", e)
                mutableState.update {
                    it.copy(busy = false, message = if (e is CoreError.Network) ReviewMessage.Offline else ReviewMessage.Failed)
                }
            }
        }
    }

    val isMine: Boolean get() = mutableState.value.detail?.item?.decidedBy == myUserId

    override fun onCleared() {
        claimJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** The server's claim lasts 3 minutes; renewing every minute keeps it while the item is open. */
        const val CLAIM_RENEW_MS = 60_000L
        const val MAX_FRAMES = 24
    }
}
