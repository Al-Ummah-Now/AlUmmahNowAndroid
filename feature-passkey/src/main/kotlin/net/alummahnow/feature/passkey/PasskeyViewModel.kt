package net.alummahnow.feature.passkey

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.auth.passkey.PasskeyAuthenticator
import net.alummahnow.core.bridge.auth.passkey.PasskeyError
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.StudioAccount

/** What went wrong, as the dialog shows it. [offerOtherWay] adds "Sign in another way" (sign-in problems). */
enum class PasskeyProblem(@param:StringRes val title: Int, @param:StringRes val body: Int, val offerOtherWay: Boolean) {
    NO_PASSKEY(R.string.passkey_none_title, R.string.passkey_none, true),
    SIGN_IN_FAILED(R.string.passkey_signin_problem_title, R.string.passkey_signin_failed, true),
    SIGN_IN_UNSUPPORTED(R.string.passkey_signin_problem_title, R.string.passkey_signin_unsupported, true),
    SIGN_IN_RATE_LIMITED(R.string.passkey_signin_problem_title, R.string.passkey_rate_limited, true),
    SIGN_IN_OFFLINE(R.string.passkey_signin_problem_title, R.string.passkey_offline, true),
    ADD_FAILED(R.string.passkey_add, R.string.passkey_add_failed, false),
    ADD_UNSUPPORTED(R.string.passkey_add, R.string.passkey_add_unsupported, false),
    ALREADY_ADDED(R.string.passkey_add, R.string.passkey_exists, false),
    LIMIT(R.string.passkey_add, R.string.passkey_limit, false),
    ADD_RATE_LIMITED(R.string.passkey_add, R.string.passkey_rate_limited, false),
    ADD_OFFLINE(R.string.passkey_add, R.string.passkey_offline, false),
    OTHER_ACCOUNT(R.string.passkey_add, R.string.passkey_other_account, false)
}

sealed interface PasskeyUiState {
    data object Idle : PasskeyUiState

    /** The OS sheet is up or the server is checking. */
    data class Working(val adding: Boolean) : PasskeyUiState

    data class Problem(val problem: PasskeyProblem) : PasskeyUiState

    /** Adding needs a sign-in under 15 minutes old: "Sign in again", then the add runs again by itself. */
    data object NeedsSignInAgain : PasskeyUiState

    /** The browser re-sign-in is open; [userId] must come back. */
    data class AwaitingSignIn(val userId: String?) : PasskeyUiState

    /** Signed in again as the same person: the screen retries the add with its authenticator. */
    data object RetryAdd : PasskeyUiState

    data object Added : PasskeyUiState
}

/**
 * Native passkey sign-in and "Add a passkey" for Studio and Admin. Cancel in the OS sheet is silent; everything else is
 * an in-app dialog. The authenticator is passed per call (it holds the Activity), never kept.
 */
class PasskeyViewModel(private val session: AccountSession) : ViewModel() {
    private val mutableState = MutableStateFlow<PasskeyUiState>(PasskeyUiState.Idle)
    val state: StateFlow<PasskeyUiState> = mutableState.asStateFlow()

    fun signIn(authenticator: PasskeyAuthenticator, onSignedIn: (StudioAccount) -> Unit = {}) {
        if (mutableState.value is PasskeyUiState.Working) return
        mutableState.value = PasskeyUiState.Working(adding = false)
        viewModelScope.launch {
            mutableState.value = try {
                val account = session.signInWithPasskey(authenticator)
                AunLog.i("passkey", "signed in")
                onSignedIn(account)
                PasskeyUiState.Idle
            } catch (e: PasskeyError) {
                signInProblem(e)
            } catch (e: CoreError) {
                AunLog.e("passkey", "sign-in failed", e)
                PasskeyUiState.Problem(if (e is CoreError.Network) PasskeyProblem.SIGN_IN_OFFLINE else PasskeyProblem.SIGN_IN_FAILED)
            }
        }
    }

    fun add(authenticator: PasskeyAuthenticator) {
        if (mutableState.value is PasskeyUiState.Working) return
        mutableState.value = PasskeyUiState.Working(adding = true)
        viewModelScope.launch {
            mutableState.value = try {
                session.addPasskey(authenticator)
                PasskeyUiState.Added
            } catch (e: PasskeyError) {
                addProblem(e)
            } catch (e: CoreError) {
                AunLog.e("passkey", "add failed", e)
                PasskeyUiState.Problem(if (e is CoreError.Network) PasskeyProblem.ADD_OFFLINE else PasskeyProblem.ADD_FAILED)
            }
        }
    }

    /** "Sign in again" was tapped; the app opens its browser step-up next. */
    fun signInAgainStarted() {
        mutableState.value = PasskeyUiState.AwaitingSignIn(session.active.value?.userId)
    }

    /** A sign-in finished. If it was the re-sign-in for an add, retry as long as it is the same person. */
    fun signedIn(account: StudioAccount?) {
        val waiting = mutableState.value as? PasskeyUiState.AwaitingSignIn ?: return
        mutableState.value = when {
            account == null -> PasskeyUiState.Problem(PasskeyProblem.ADD_FAILED)
            waiting.userId != null && account.userId != waiting.userId -> PasskeyUiState.Problem(PasskeyProblem.OTHER_ACCOUNT)
            else -> PasskeyUiState.RetryAdd
        }
    }

    fun dismiss() {
        mutableState.value = PasskeyUiState.Idle
    }

    private fun signInProblem(e: PasskeyError): PasskeyUiState = when (e) {
        is PasskeyError.Cancelled -> PasskeyUiState.Idle
        is PasskeyError.NoCredential -> PasskeyUiState.Problem(PasskeyProblem.NO_PASSKEY)
        is PasskeyError.Unsupported -> PasskeyUiState.Problem(PasskeyProblem.SIGN_IN_UNSUPPORTED)
        is PasskeyError.RateLimited -> PasskeyUiState.Problem(PasskeyProblem.SIGN_IN_RATE_LIMITED)
        else -> {
            AunLog.e("passkey", "sign-in refused", e)
            PasskeyUiState.Problem(PasskeyProblem.SIGN_IN_FAILED)
        }
    }

    private fun addProblem(e: PasskeyError): PasskeyUiState = when {
        e is PasskeyError.Cancelled -> PasskeyUiState.Idle
        e is PasskeyError.StepUpRequired -> PasskeyUiState.NeedsSignInAgain
        e is PasskeyError.AlreadyRegistered -> PasskeyUiState.Problem(PasskeyProblem.ALREADY_ADDED)
        e is PasskeyError.Rejected && e.code == "exists" -> PasskeyUiState.Problem(PasskeyProblem.ALREADY_ADDED)
        e is PasskeyError.Rejected && e.code == "limit" -> PasskeyUiState.Problem(PasskeyProblem.LIMIT)
        e is PasskeyError.Rejected && e.code in STALE -> PasskeyUiState.NeedsSignInAgain
        e is PasskeyError.Unsupported || e is PasskeyError.NoCredential -> PasskeyUiState.Problem(PasskeyProblem.ADD_UNSUPPORTED)
        e is PasskeyError.RateLimited -> PasskeyUiState.Problem(PasskeyProblem.ADD_RATE_LIMITED)
        else -> {
            AunLog.e("passkey", "add refused", e)
            PasskeyUiState.Problem(PasskeyProblem.ADD_FAILED)
        }
    }

    private companion object {
        /** The web route's word for an old sign-in; treated like `step_up_required`. */
        val STALE = setOf("stale_sign_in")
    }
}
