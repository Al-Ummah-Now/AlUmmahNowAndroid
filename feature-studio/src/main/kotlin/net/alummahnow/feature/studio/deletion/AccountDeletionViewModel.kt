package net.alummahnow.feature.studio.deletion

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.core.bridge.studio.deletion.AccountDeletion
import net.alummahnow.core.bridge.studio.deletion.DeletionOutcome
import net.alummahnow.core.bridge.studio.deletion.ErasedSoFar
import net.alummahnow.core.bridge.studio.deletion.Leftover
import net.alummahnow.feature.studio.R

/** What the dialog's action does now. */
enum class DeleteAction {
    DELETE,

    /** Asks for a recent sign-in first; nothing is removed by it. */
    SIGN_IN,

    /** After what stays behind was said. */
    DELETE_ANYWAY
}

data class DeleteAccountUiState(
    /** The account the dialog is for; null while it is closed. */
    val target: StudioAccount? = null,
    /** How many channels it owns, when that is known. */
    val ownedChannels: Int? = null,
    val working: Boolean = false,
    /** The person asked to stop: the request that is out ends first. */
    val stopping: Boolean = false,
    /** Passes that left more to remove. */
    val passes: Int = 0,
    val restarted: Boolean = false,
    val action: DeleteAction = DeleteAction.DELETE,
    val leftover: Set<Leftover> = emptySet(),
    @param:StringRes val message: Int? = null,
    /** [message] says what went wrong (announced at once, in the error colour). */
    val isError: Boolean = false,
    /** Not before then (the server asked). */
    val notBeforeMs: Long = 0,
    /** Shown once outside the dialog. */
    val notice: DeletionNotice? = null
)

/** Proof of what happened, shown once. Never stored: a saved flag could be shown for a deletion that wasn't. */
data class DeletionNotice(@param:StringRes val text: Int, val withPurge: Boolean, val serial: Int)

/** Deletes the account the person confirmed. A redirect never starts it: they confirm again after a sign-in. */
class AccountDeletionViewModel(
    private val session: AccountSession,
    private val deletion: AccountDeletion,
    private val forgetClient: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val mutable = MutableStateFlow(DeleteAccountUiState())
    val state: StateFlow<DeleteAccountUiState> = mutable.asStateFlow()

    /** The sign-in started for a deletion, bound to its OAuth `state` and a short time. */
    private class StepUp(val userId: String, val oauthState: String, val expiresAtMs: Long)

    private var stepUp: StepUp? = null
    private var serial = 0

    fun open(account: StudioAccount, ownedChannels: Int?) {
        if (mutable.value.working) return
        stepUp = null
        mutable.update { DeleteAccountUiState(target = account, ownedChannels = ownedChannels, notice = it.notice) }
    }

    /** Cancel, or back. While a request is out it asks to stop instead; that request has a limited time. */
    fun close() {
        if (mutable.value.working) {
            mutable.update { it.copy(stopping = true) }
            return
        }
        stepUp = null
        mutable.update { DeleteAccountUiState(notice = it.notice) }
    }

    fun noticeShown() = mutable.update { it.copy(notice = null) }

    /** The dialog's action. [openBrowser] opens a sign-in or the logout page. */
    fun confirm(openBrowser: (String) -> Boolean) {
        val s = mutable.value
        val target = s.target ?: return
        if (s.working || s.notBeforeMs > nowMs()) return
        if (s.action == DeleteAction.SIGN_IN) startSignIn(target, openBrowser) else start(target, s.leftover, openBrowser)
    }

    private fun start(target: StudioAccount, accepted: Set<Leftover>, openBrowser: (String) -> Boolean) {
        mutable.update { it.copy(working = true, stopping = false, passes = 0, restarted = false, message = null, isError = false) }
        viewModelScope.launch {
            val outcome = try {
                deletion.run(
                    userId = target.userId,
                    label = target.email?.takeIf { it.isNotBlank() } ?: target.label,
                    acceptLeftover = accepted,
                    stopRequested = { mutable.value.stopping },
                    onPass = { pass, restarted -> mutable.update { it.copy(passes = pass, restarted = it.restarted || restarted) } }
                )
            } finally {
                mutable.update { it.copy(working = false, stopping = false) }
            }
            if (outcome is DeletionOutcome.Deleted) {
                deleted(target.userId, outcome)
                // The browser's session ends too; the account itself is gone already, so nothing depends on it.
                session.logoutUrl?.let(openBrowser)
            } else {
                mutable.update { it.after(outcome) }
            }
        }
    }

    /** Asks what became of a deletion whose answer was lost; one that worked is said and cleaned up. */
    fun settle() {
        if (mutable.value.working) return
        viewModelScope.launch { deletion.settleLost().forEach { (userId, outcome) -> deleted(userId, outcome) } }
    }

    /** Puts "it isn't known what became of it" away. */
    fun dismiss(userId: String) = deletion.dismiss(userId)

    private fun deleted(userId: String, outcome: DeletionOutcome.Deleted) {
        forgetClient(userId)
        val text = when {
            outcome.elsewhere -> R.string.studio_delete_done_elsewhere
            outcome.leftover.isEmpty() -> R.string.studio_delete_done
            Leftover.CHANNEL !in outcome.leftover -> R.string.studio_delete_done_other
            Leftover.OTHER !in outcome.leftover -> R.string.studio_delete_done_channel
            else -> R.string.studio_delete_done_both
        }
        val notice = DeletionNotice(text, withPurge = !outcome.elsewhere, serial = ++serial)
        // Another account's dialog stays open.
        mutable.update {
            val other = it.target != null && it.target.userId != userId
            if (other) it.copy(notice = notice) else DeleteAccountUiState(notice = notice)
        }
    }

    private fun DeleteAccountUiState.after(outcome: DeletionOutcome): DeleteAccountUiState = when (outcome) {
        is DeletionOutcome.Deleted -> this
        is DeletionOutcome.NeedsSignIn -> copy(
            action = DeleteAction.SIGN_IN,
            message = signInText(outcome.erased)
        )
        is DeletionOutcome.OtherAccount -> error(R.string.studio_delete_other_account)
        is DeletionOutcome.LeftBehind -> copy(
            action = DeleteAction.DELETE_ANYWAY,
            leftover = outcome.leftover,
            message = when {
                Leftover.CHANNEL !in outcome.leftover -> R.string.studio_delete_left_other
                Leftover.OTHER !in outcome.leftover -> R.string.studio_delete_left_channel
                else -> R.string.studio_delete_left_both
            },
            isError = false
        )
        is DeletionOutcome.Wait -> error(
            if (outcome.erased == ErasedSoFar.NOTHING) R.string.studio_delete_busy_nothing else R.string.studio_delete_wait
        ).copy(notBeforeMs = outcome.untilMs)
        is DeletionOutcome.Paused -> error(if (outcome.stopped) R.string.studio_delete_stopped else R.string.studio_delete_more)
        is DeletionOutcome.Failed -> error(
            when (outcome.erased) {
                ErasedSoFar.NOTHING -> R.string.studio_delete_error_nothing
                ErasedSoFar.SOME -> R.string.studio_delete_error_started
                ErasedSoFar.CONTENT -> R.string.studio_delete_error_content_erased
            }
        )
        DeletionOutcome.Unknown -> error(R.string.studio_delete_error_unknown)
        is DeletionOutcome.NoRecord -> error(
            if (outcome.erased == ErasedSoFar.NOTHING) R.string.studio_delete_error_nothing else R.string.studio_delete_unavailable
        )
    }

    private fun DeleteAccountUiState.error(@StringRes text: Int) = copy(action = DeleteAction.DELETE, message = text, isError = true)

    private fun signInText(erased: ErasedSoFar): Int = when (erased) {
        ErasedSoFar.NOTHING -> R.string.studio_delete_sign_in
        ErasedSoFar.SOME -> R.string.studio_delete_sign_in_started
        ErasedSoFar.CONTENT -> R.string.studio_delete_sign_in_content_erased
    }

    private fun startSignIn(target: StudioAccount, openBrowser: (String) -> Boolean) {
        runCatching { session.beginStepUp(AccountDeletion.FIRST_MAX_AGE_S) }.fold(
            onSuccess = { url ->
                val oauthState = queryParam(url, "state")
                // Without a state the answer could not be told from another sign-in's: none is started.
                if (oauthState == null || !openBrowser(url)) {
                    mutable.update { it.error(R.string.studio_signin_no_browser).copy(action = DeleteAction.SIGN_IN) }
                } else {
                    stepUp = StepUp(target.userId, oauthState, nowMs() + STEP_UP_TTL_MS)
                }
            },
            onFailure = { e ->
                AunLog.e("auth", "sign-in for a deletion could not start", e)
                mutable.update { it.error(R.string.studio_signin_failed).copy(action = DeleteAction.SIGN_IN) }
            }
        )
    }

    /** A sign-in finished as [signedIn]. Only the one started for this deletion counts, and it only lets them confirm. */
    fun signInCompleted(redirectUrl: String, signedIn: StudioAccount?) {
        val pending = stepUp?.takeIf { queryParam(redirectUrl, "state") == it.oauthState } ?: return
        stepUp = null
        if (mutable.value.target?.userId != pending.userId) return
        if (signedIn?.userId != pending.userId) {
            // Someone else: nothing of either account is erased, and the first one is active again.
            runCatching { session.switchTo(pending.userId) }.onFailure { AunLog.e("auth", "switch back failed", it) }
            mutable.value = DeleteAccountUiState(
                notice = DeletionNotice(R.string.studio_delete_signed_in_other, withPurge = false, serial = ++serial)
            )
            return
        }
        if (nowMs() <=
            pending.expiresAtMs
        ) {
            mutable.update { it.copy(action = DeleteAction.DELETE, message = R.string.studio_delete_signed_in_again, isError = false) }
        }
    }

    /** The sign-in started for this deletion failed or was cancelled in the browser. */
    fun signInFailed() {
        if (stepUp == null) return
        stepUp = null
        mutable.update { it.copy(message = R.string.studio_delete_sign_in_failed, isError = true) }
    }

    companion object {
        private const val STEP_UP_TTL_MS = 5 * 60_000L

        private fun queryParam(url: String, key: String): String? = url.substringAfter('?', "").substringBefore('#')
            .split('&')
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }
}
