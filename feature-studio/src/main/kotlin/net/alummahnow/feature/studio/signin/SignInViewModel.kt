package net.alummahnow.feature.studio.signin

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

sealed interface SignInUiState {
    data object Idle : SignInUiState

    /** The browser is open; waiting for the redirect. */
    data object InBrowser : SignInUiState

    /** Exchanging the code for tokens. */
    data object Completing : SignInUiState
    data class Error(@param:StringRes val message: Int) : SignInUiState
}

/** Browser (Custom Tabs) sign-in with PKCE through aun-auth. */
class SignInViewModel(private val session: AccountSession) : ViewModel() {
    private val mutableState = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val state: StateFlow<SignInUiState> = mutableState.asStateFlow()

    /** Returns the URL to open in a Custom Tab, or null on failure (state shows why). */
    fun start(mode: SignInMode): String? = try {
        session.beginSignIn(mode).also { mutableState.value = SignInUiState.InBrowser }
    } catch (e: CoreError) {
        mutableState.value = SignInUiState.Error(e.studioMessage())
        null
    } catch (e: StudioError) {
        mutableState.value = SignInUiState.Error(e.studioMessage())
        null
    }

    /** The app received `alummahnow-studio://auth/callback?...`. */
    fun complete(redirectUrl: String, onDone: () -> Unit = {}) {
        mutableState.value = SignInUiState.Completing
        viewModelScope.launch {
            attempt("auth", "sign-in") { session.completeSignIn(redirectUrl) }.fold(
                onSuccess = {
                    mutableState.value = SignInUiState.Idle
                    onDone()
                },
                onFailure = { e ->
                    mutableState.value = SignInUiState.Error(
                        when {
                            redirectUrl.contains("error=access_denied") -> R.string.studio_signin_cancelled
                            e is CoreError.Network -> e.studioMessage()
                            else -> R.string.studio_signin_failed
                        }
                    )
                }
            )
        }
    }

    /** The user came back from the browser without a redirect (closed the tab). */
    fun browserClosed() {
        if (mutableState.value == SignInUiState.InBrowser) mutableState.value = SignInUiState.Idle
    }

    fun noBrowser() {
        mutableState.value = SignInUiState.Error(R.string.studio_signin_no_browser)
    }

    class Factory(private val session: AccountSession) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SignInViewModel(session) as T
    }
}
